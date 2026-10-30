"""Read fal billed events, not the advertised price or our reservation allowance."""
import hashlib
import json
from datetime import timedelta
from decimal import Decimal, ROUND_HALF_UP

import requests
from django.conf import settings
from django.db.models import F, Q
from django.utils import timezone
from django.utils.dateparse import parse_datetime

from . import fal_catalog, finance
from .models import Job

URL = "https://api.fal.ai/v1/models/billing-events"


def configured():
    return bool(settings.FAL_BILLING_KEY and settings.USD_INR_RATE > 0)


def fetch_events(jobs, start, end):
    identifiers = {job.provider_id for job in jobs}
    if len(identifiers) != len(jobs):
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
    jobs = list(Job.objects.filter(created_at__gte=start, settled_at__isnull=False, quote__payload__model__startswith="fal:")
                .exclude(provider_id="").exclude(cost_record__source="invoice")
                .filter(Q(cost_checked_at__isnull=True) | Q(cost_checked_at__lt=end - timedelta(hours=6)))
                .select_related("quote", "cost_record").order_by(F("cost_checked_at").asc(nulls_first=True), "created_at")[:limit])
    added = missing = 0
    for offset in range(0, len(jobs), 50):
        batch = jobs[offset:offset + 50]
        events = fetch_events(batch, start - timedelta(hours=1), end)
        for job in batch:
            matched = sorted([event for event in events if event["request_id"] == job.provider_id], key=lambda item: (item["timestamp"], item["endpoint"], item["unit"]))
            if matched:
                endpoint = job.provider_context.get("endpoint")
                if not endpoint:
                    endpoint, _ = fal_catalog.request(job.quote.payload, "source-present" if job.quote.payload.get("input_key") else None)
                if any(row["endpoint"] != endpoint for row in matched):
                    raise ValueError("Provider endpoint mismatch; no cost was attributed to this job")
                # Preserve the original FX valuation on later refreshes. Manual invoice cost wins.
                fx = job.cost_record.usd_inr if job.cost_record and job.cost_record.usd_inr else settings.USD_INR_RATE
                usd = sum((Decimal(row["cost"]) for row in matched), Decimal(0))
                paise = int((usd * fx * 100).quantize(Decimal("1"), rounding=ROUND_HALF_UP))
                fingerprint = hashlib.sha256(json.dumps([matched, str(fx.normalize())], sort_keys=True).encode()).hexdigest()
                _, created = finance.record_cost(job.pk, paise, "fal request " + job.provider_id, source="fal_billing", original_usd=usd, usd_inr=fx, fingerprint=fingerprint)
                added += int(created)
            else:
                # No billing row is NOT evidence that a job was free; keep it unreconciled.
                missing += 1
            Job.objects.filter(pk=job.pk).update(cost_checked_at=end)
    return {"checked": len(jobs), "revisions": added, "awaiting_billing": missing}
