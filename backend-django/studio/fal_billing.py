"""Read fal billed events, not the advertised price or our reservation allowance."""
import hashlib
import json
from datetime import timedelta
from decimal import Decimal, ROUND_HALF_UP

import requests
from django.conf import settings
from django.db.models import F, Q
from django.db import transaction
from django.utils import timezone
from django.utils.dateparse import parse_datetime

from . import fal_catalog, finance
from .models import Job

URL = "https://api.fal.ai/v1/models/billing-events"


def request_contracts(job):
    if job.quote.payload.get("model") == "motion:long-story":
        from .long_story import VIDEO_ENDPOINT, TEXT_VIDEO_ENDPOINT
        contracts = {step["id"]: step.get("endpoint", VIDEO_ENDPOINT)
                     for step in job.provider_context.get("steps", []) if step.get("id") and step["kind"] == "scene"}
        if any(endpoint not in (VIDEO_ENDPOINT, TEXT_VIDEO_ENDPOINT) for endpoint in contracts.values()):
            raise ValueError("Unexpected story scene billing endpoint")
        return contracts
    endpoint = job.provider_context.get("endpoint")
    if not endpoint:
        endpoint, _ = fal_catalog.request(job.quote.payload, "source-present" if job.quote.payload.get("input_key") else None)
    return {job.provider_id: endpoint}


def configured():
    return bool(settings.FAL_BILLING_KEY and settings.USD_INR_RATE > 0)


def fetch_events(jobs, start, end):
    rows = [identifier for job in jobs for identifier in request_contracts(job)]
    identifiers = set(rows)
    if len(identifiers) != len(rows):
        raise ValueError("Duplicate provider request IDs require manual reconciliation")
    params = {"request_id": ",".join(sorted(identifiers)), "start": start.isoformat(), "end": end.isoformat(), "limit": 200}
    seen, cursors = {}, set()
    for _ in range(50):
        with requests.get(URL, params=params, headers={"Authorization": f"Key {settings.FAL_BILLING_KEY}"}, timeout=(5, 30), stream=True, allow_redirects=False) as response:
            if response.status_code != 200:
                raise ValueError("fal billing lookup failed; check BILLING permissions and retry.")
            raw = bytearray()
            for chunk in response.iter_content(65536):
                raw.extend(chunk)
                if len(raw) > 2 * 1024**2: raise ValueError("Billing response too large")
            data = json.loads(raw, parse_float=Decimal)
        events = data.get("billing_events")
        if not isinstance(events, list) or len(events) > 200:
            raise ValueError("Unexpected billing response")
        for event in events:
            if event.get("request_id") not in identifiers:
                raise ValueError("Billing response includes an unrequested job")
            stamp = parse_datetime(event.get("timestamp", ""))
            if stamp is None or timezone.is_naive(stamp): raise ValueError("Invalid billing timestamp")
            amount = Decimal(str(event["cost_total"]))
            if not amount.is_finite() or not 0 <= amount < 10**9:
                raise ValueError("Invalid billed amount")
            identity = (event["request_id"], event["endpoint_id"], event["timestamp"], event.get("unit", ""))
            normalized = {"request_id": identity[0], "endpoint": identity[1], "timestamp": identity[2], "unit": identity[3], "cost": str(amount)}
            if identity in seen and seen[identity] != normalized:
                raise ValueError("Conflicting billing events require manual reconciliation")
            seen[identity] = normalized
        if data.get("has_more") is False:
            return list(seen.values())
        cursor = data.get("next_cursor")
        if not isinstance(cursor, str) or not cursor or len(cursor) > 4096 or cursor in cursors:
            raise ValueError("Invalid billing pagination")
        cursors.add(cursor); params["cursor"] = cursor
    raise ValueError("Billing export exceeds the page limit; narrow the date range")


def sync_recent(days=7, limit=100):
    if not configured(): raise ValueError("Set MOTION_AI_FAL_BILLING_KEY and a verified MOTION_AI_USD_INR_RATE first.")
    if not 1 <= days <= 89 or not 1 <= limit <= 1000: raise ValueError("Use 1–89 days and 1–1000 jobs.")
    end = timezone.now(); start = end - timedelta(days=days)
    jobs = list(Job.objects.filter(Q(quote__payload__model__startswith="fal:") | Q(quote__payload__model="motion:long-story"), created_at__gte=start, settled_at__isnull=False)
                .exclude(provider_id="").exclude(cost_record__source="invoice")
                .filter(Q(cost_checked_at__isnull=True) | Q(cost_checked_at__lt=end - timedelta(hours=6)))
                .select_related("quote", "cost_record").order_by(F("cost_checked_at").asc(nulls_first=True), "created_at")[:limit])
    added = missing = 0
    batches, batch, size = [], [], 0
    for job in jobs:
        count = len(request_contracts(job))
        if not count:
            missing += 1
            continue
        if batch and size + count > 50:
            batches.append(batch); batch, size = [], 0
        batch.append(job); size += count
    if batch: batches.append(batch)
    for batch in batches:
        events = fetch_events(batch, start - timedelta(hours=1), end)
        for job in batch:
            contracts = request_contracts(job)
            matched = sorted([event for event in events if event["request_id"] in contracts], key=lambda item: (item["timestamp"], item["endpoint"], item["unit"]))
            ambiguous = any(step.get("cost", 0) > 0 and not step.get("id") for step in job.provider_context.get("steps", []))
            if matched and set(contracts) == {row["request_id"] for row in matched} and not ambiguous:
                if any(row["endpoint"] != contracts[row["request_id"]] for row in matched):
                    raise ValueError("Provider endpoint mismatch; no cost was attributed to this job")
                # Preserve the original FX valuation on later refreshes. Manual invoice cost wins.
                fx = job.cost_record.usd_inr if job.cost_record and job.cost_record.usd_inr else settings.USD_INR_RATE
                usd = sum((Decimal(row["cost"]) for row in matched), Decimal(0))
                paise = int((usd * fx * 100).quantize(Decimal("1"), rounding=ROUND_HALF_UP))
                if job.quote.payload.get("model") == "motion:long-story":
                    # fal invoices cannot prove the separate OpenAI planning charge.
                    # Save the video subtotal, but keep the complete bill unreconciled
                    # until an owner adds both provider statements. Never underreport cost.
                    with transaction.atomic():
                        saved = Job.objects.select_for_update().get(pk=job.pk)
                        saved.provider_context = {**saved.provider_context, "fal_billed_usd": str(usd), "fal_billed_inr_paise": paise}
                        saved.cost_checked_at = end
                        saved.save(update_fields=["provider_context", "cost_checked_at"])
                    missing += 1
                    continue
                fingerprint = hashlib.sha256(json.dumps([matched, str(fx.normalize())], sort_keys=True).encode()).hexdigest()
                _, created = finance.record_cost(job.pk, paise, "fal request " + job.provider_id, source="fal_billing", original_usd=usd, usd_inr=fx, fingerprint=fingerprint)
                added += int(created)
            else:
                # No billing row is NOT evidence that a job was free; keep it unreconciled.
                missing += 1
            Job.objects.filter(pk=job.pk).update(cost_checked_at=end)
    return {"checked": len(jobs), "revisions": added, "awaiting_billing": missing}
