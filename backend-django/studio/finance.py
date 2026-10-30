"""Owner reporting: integer paise, immutable cost revisions, no inferred cash receipts."""
import hashlib
import json
from datetime import date, datetime, time, timedelta
from decimal import Decimal, ROUND_HALF_UP
from zoneinfo import ZoneInfo

from django.db import transaction
from django.db.models import BigIntegerField, Case, Count, F, Q, Sum, Value, When
from django.db.models.functions import Coalesce, TruncDate, TruncMonth, Cast
from django.utils import timezone

from .models import Account, CashEntry, Job, OwnerAudit, ProviderCost

INDIA = ZoneInfo("Asia/Kolkata")


def rupees(paise):
    if paise is None:
        return "Pending reconciliation"
    return f"₹{Decimal(paise) / 100:,.2f}"


def period(params):
    today = timezone.now().astimezone(INDIA).date()
    start = date.fromisoformat(params.get("start") or today.replace(day=1).isoformat())
    end = date.fromisoformat(params.get("end") or today.isoformat())
    if start > end or (end - start).days > 366 or not (2000 <= start.year <= end.year <= 2100):
        raise ValueError("Choose an ordered date range of at most 367 days, between 2000 and 2100.")
    account = params.get("user", "").strip()
    if account and (len(account) > 128 or not Account.objects.filter(pk=account).exists()):
        raise ValueError("Choose an existing user ID.")
    group = params.get("group", "day")
    if group not in ("day", "month", "user"):
        raise ValueError("Choose day, month or user grouping.")
    return {"start": start, "end": end, "from": datetime.combine(start, time.min, INDIA),
            "until": datetime.combine(end + timedelta(days=1), time.min, INDIA), "user": account, "group": group}


def job_query(filters):
    query = Job.objects.filter(settled_at__gte=filters["from"], settled_at__lt=filters["until"])
    return query.filter(account_id=filters["user"]) if filters["user"] else query


def cash_query(filters):
    query = CashEntry.objects.filter(occurred_at__gte=filters["from"], occurred_at__lt=filters["until"])
    return query.filter(account_id=filters["user"]) if filters["user"] else query


def summed(expression, **kwargs):
    return Coalesce(Sum(expression, output_field=BigIntegerField(), **kwargs), Value(0), output_field=BigIntegerField())


def job_metrics():
    overhead = Case(When(state="cancelled", then=Value(0)), default=F("quote__overhead_paise"), output_field=BigIntegerField())
    # Whole-paise ceiling for the configured fee allocation; not a store statement.
    fee = Cast((F("charged_paise") * F("quote__fee_bps") + Value(9999)) / Value(10000), BigIntegerField())
    return dict(jobs=Count("pk"), succeeded=Count("pk", filter=Q(state="succeeded")),
                failed=Count("pk", filter=Q(state="failed")), cancelled=Count("pk", filter=Q(state="cancelled")),
                charged=summed("charged_paise"), estimated_provider=summed("actual_cost_paise"),
                overhead=summed(overhead), estimated_fee=summed(fee), billed_provider=summed("cost_record__amount_paise"),
                pending_cost=Count("pk", filter=Q(cost_record__isnull=True) & ~Q(state="cancelled")))


def cash_metrics():
    return {key: summed("amount_paise", filter=Q(kind=key)) for key, _ in CashEntry.KINDS}


def enrich(row):
    row["estimated_margin"] = row["charged"] - row["estimated_provider"] - row["overhead"] - row["estimated_fee"]
    row["reconciled_margin"] = None if row["pending_cost"] else row["charged"] - row["billed_provider"] - row["overhead"] - row["estimated_fee"]
    row["margin_percent"] = (f"{Decimal(row['estimated_margin']) * 100 / row['charged']:.2f}%" if row["charged"] else "—")
    row["cash_net"] = row["receipt"] - row["refund"] - row["store_fee"] - row["expense"] - row["provider_topup"]
    return row


def summary(filters):
    jobs, cash = job_query(filters), cash_query(filters)
    totals = enrich({**jobs.aggregate(**job_metrics()), **cash.aggregate(**cash_metrics())})
    # DB performs aggregation; no per-job iteration. User view is explicitly bounded.
    def bucket(field, kind):
        if kind == "user": return F("account_id")
        return (TruncMonth if kind == "month" else TruncDate)(field, tzinfo=INDIA)
    grouped_jobs = jobs.annotate(bucket=bucket("settled_at", filters["group"])).values("bucket").annotate(**job_metrics()).order_by("bucket")
    grouped_cash = cash.annotate(bucket=bucket("occurred_at", filters["group"])).values("bucket").annotate(**cash_metrics()).order_by("bucket")
    limit = 200 if filters["group"] == "user" else 400
    jrows, crows = list(grouped_jobs[:limit + 1]), list(grouped_cash[:limit + 1])
    truncated = len(jrows) > limit or len(crows) > limit
    rows = {}
    defaults = {name: 0 for name in (*job_metrics(), *cash_metrics())}
    for source in (jrows[:limit], crows[:limit]):
        for values in source:
            key = values["bucket"]
            rows.setdefault(key, {**defaults, "bucket": key}).update(values)
    names = dict(Account.objects.filter(pk__in=[key for key in rows if key]).values_list("pk", "email")) if filters["group"] == "user" else {}
    for key, row in rows.items():
        row["label"] = names.get(key, key or "Unallocated business costs")
        enrich(row)
    return totals, sorted(rows.values(), key=lambda row: str(row["bucket"] or "")), truncated


@transaction.atomic
def record_cost(job_id, amount_paise, evidence, owner=None, source="invoice", original_usd=None, usd_inr=None, fingerprint=None):
    if type(amount_paise) is not int or not 0 <= amount_paise <= 10**14 or not evidence.strip():
        raise ValueError("A non-negative amount and evidence reference are required.")
    job = Job.objects.select_for_update(of=("self",)).select_related("cost_record").get(pk=job_id)
    if job.state not in ("succeeded", "failed", "cancelled"):
        raise ValueError("Reconcile a job after it settles.")
    signature = fingerprint or hashlib.sha256(json.dumps([amount_paise, evidence, source, str(original_usd), str(usd_inr)]).encode()).hexdigest()
    current = job.cost_record
    if current and (current.fingerprint == signature or (source == "fal_billing" and current.source == "invoice")):
        return current, False
    record = ProviderCost.objects.create(job=job, amount_paise=amount_paise, evidence=evidence[:250], recorded_by=owner,
                                         source=source, original_usd=original_usd, usd_inr=usd_inr, fingerprint=signature)
    job.cost_record = record
    job.save(update_fields=["cost_record"])
    if owner:
        OwnerAudit.objects.create(owner=owner, action="record_provider_cost", reference=str(record.pk))
    return record, True


@transaction.atomic
def reverse_cash(entry_id, owner, note):
    item = CashEntry.objects.select_for_update().get(pk=entry_id)
    if item.reversal_of_id or hasattr(item, "reversal"):
        raise ValueError("This entry is already reversed or is itself a reversal.")
    result = CashEntry.objects.create(occurred_at=item.occurred_at, kind=item.kind, amount_paise=-item.amount_paise,
                                     account=item.account, purchase=item.purchase, reference="reversal:" + str(item.pk),
                                     note=note, reversal_of=item, recorded_by=owner)
    OwnerAudit.objects.create(owner=owner, action="reverse_cash", reference=str(result.pk))
    return result
