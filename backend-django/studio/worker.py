import uuid
from datetime import timedelta
from django.conf import settings
from django.db import transaction
from django.db.models import Q
from django.utils import timezone
from . import provider, storage
from .models import Job
from .services import finish_job, TERMINAL

@transaction.atomic
def claim():
    now = timezone.now()
    job = Job.objects.select_for_update(skip_locked=True).filter(next_attempt_at__lte=now).filter(Q(lease_until__isnull=True) | Q(lease_until__lt=now)).exclude(state__in=TERMINAL).order_by("next_attempt_at").first()
    if not job:
        return None
    was_queued = job.state == "queued"
    if was_queued:
        job.state = "submitting"
    job.lease_id = uuid.uuid4()
    job.lease_until = now + timedelta(seconds=settings.GENERATION_LEASE_SECONDS)
    job.attempts += 1
    job.save()
    return job.pk, job.lease_id, was_queued

def run_one():
    claimed = claim()
    if claimed is None:
        return False
    job_id, lease, fresh = claimed
    job = Job.objects.select_related("quote").get(pk=job_id)
    try:
        result = provider.submit(job) if fresh else provider.reconcile(job)
        # Never blindly resubmit after a timeout/crash: a provider may already have billed.
        if result["state"] == "not_found":
            reschedule(job, lease, "uncertain", "provider_status_unknown")
            return True
        if result["state"] in TERMINAL:
            cost = result.get("cost_paise")
            if type(cost) is not int or not 0 <= cost <= job.quote.provider_cost_paise or result.get("currency") != "INR":
                raise ValueError("Invalid cost")
            asset = storage.provider_output(job, result.get("output", {})) if result["state"] == "succeeded" else None
            finish_job(job.pk, result["state"], cost, asset, "provider_failed" if result["state"] == "failed" else "", lease)
        else:
            provider_id = result.get("id", "")
            if not isinstance(provider_id, str) or len(provider_id) > 200:
                raise ValueError("Invalid provider ID")
            Job.objects.filter(pk=job.pk, lease_id=lease).update(provider_id=provider_id)
            reschedule(job, lease, "running")
    except Exception:
        # Keep the hold until the provider gives a definitive result. Alert on old uncertain jobs.
        reschedule(job, lease, "uncertain", "provider_status_unknown")
    return True

def reschedule(job, lease, state, error=""):
    delay = min(900, 15 * 2 ** min(job.attempts, 6))
    Job.objects.filter(pk=job.pk, lease_id=lease).exclude(state__in=TERMINAL).update(state=state, error_code=error, lease_id=None, lease_until=None, next_attempt_at=timezone.now() + timedelta(seconds=delay))
