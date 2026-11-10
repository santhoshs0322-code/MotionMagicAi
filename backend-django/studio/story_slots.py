"""Two asynchronous scene permits and one fenced assembly permit for all workers.

Scene permits never expire: a paid request with an unknown ID needs reconciliation,
not another POST. Failed parents' known requests are drained without downloading.
"""
import logging
import re
import uuid
from datetime import timedelta

import requests
from django.conf import settings
from django.db import transaction
from django.utils import timezone

from .models import Job, StoryWorkSlot

log = logging.getLogger(__name__)
TERMINAL = ("succeeded", "failed", "cancelled")
SCENE_NAMES = ("scene-1", "scene-2")
EMPTY = dict(job=None, token=None, request_id="", acquired_at=None, expires_at=None)


@transaction.atomic
def reserve_scene(job, step):
    # One primary-key row serializes acquisitions across all worker processes.
    StoryWorkSlot.objects.select_for_update().get(pk="scene-1")
    if StoryWorkSlot.objects.filter(name__startswith="scene-", job__isnull=False).count() >= 2:
        return False
    slot = StoryWorkSlot.objects.filter(name__in=SCENE_NAMES, job__isnull=True).order_by("name").first()
    if slot is None:
        return False
    slot.job_id, slot.token = job.pk, uuid.UUID(step["asset_job_id"])
    slot.acquired_at, slot.request_id = timezone.now(), ""
    slot.next_check_at = timezone.now()
    slot.save()
    job.provider_context["steps"].append(step)
    job.provider_context["spent_allowance"] += step["cost"]
    from .long_story import save
    save(job)  # Reservation and paid intent commit together, before the POST.
    return True


def available():
    # This is only a scheduling hint. reserve_scene enforces the actual limit.
    return StoryWorkSlot.objects.filter(name__startswith="scene-", job__isnull=False).count() < 2


def release_scene(job, step):
    StoryWorkSlot.objects.filter(job_id=job.pk, token=step["asset_job_id"], name__startswith="scene-").update(**EMPTY)


@transaction.atomic
def record_request(job, step):
    from .long_story import save
    save(job)
    if step["kind"] == "scene":
        changed = StoryWorkSlot.objects.filter(job_id=job.pk, token=step["asset_job_id"], name__startswith="scene-").update(request_id=step["id"])
        if changed != 1:
            raise ValueError("Scene request lost its global permit")


@transaction.atomic
def complete_scene(job, step):
    from .long_story import save
    job.provider_context["scenes_done"] = sum(row["kind"] == "scene" and row["state"] == "done" for row in job.provider_context["steps"])
    save(job)
    release_scene(job, step)


@transaction.atomic
def acquire_assembly(job):
    slot = StoryWorkSlot.objects.select_for_update().get(pk="assembly")
    now = timezone.now()
    if slot.job_id and (slot.expires_at is None or slot.expires_at > now):
        return False
    deadline = Job.objects.filter(pk=job.pk, lease_id=job.lease_id, lease_until__gt=now).values_list("lease_until", flat=True).first()
    if deadline is None:
        raise ValueError("Assembly worker lease is unavailable")
    slot.job_id, slot.token, slot.acquired_at = job.pk, job.lease_id, now
    # CPU commands are bounded to 240s and check the job lease before starting.
    slot.expires_at = deadline + timedelta(seconds=300)
    slot.save()
    return True


def release_assembly(job):
    StoryWorkSlot.objects.filter(pk="assembly", job_id=job.pk, token=job.lease_id).update(**EMPTY)


def require_assembly_lease(job, command_seconds):
    now = timezone.now()
    if not StoryWorkSlot.objects.filter(pk="assembly", job_id=job.pk, token=job.lease_id, expires_at__gt=now).exists():
        raise RuntimeError("Assembly permit is no longer owned by this worker")
    if not Job.objects.filter(pk=job.pk, lease_id=job.lease_id,
            lease_until__gt=now + timedelta(seconds=command_seconds + 30)).exists():
        raise RuntimeError("Assembly lease must be renewed before starting a CPU command")


def reap_one():
    """Return a failed parent's permit only after its provider request has ended."""
    now = timezone.now()
    slot = StoryWorkSlot.objects.filter(name__startswith="scene-", job__state__in=TERMINAL,
        next_check_at__lte=now).exclude(request_id="").order_by("next_check_at", "name").first()
    if slot is None:
        return False
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,160}", slot.request_id):
        return False
    changed = StoryWorkSlot.objects.filter(pk=slot.pk, token=slot.token, request_id=slot.request_id,
        next_check_at=slot.next_check_at).update(next_check_at=now + timedelta(seconds=30))
    if not changed:
        return False
    try:
        from . import fal
        url = f"{fal.QUEUE}/fal-ai/kling-video/requests/{slot.request_id}/status"
        with requests.get(url, headers={"Authorization": f"Key {settings.FAL_KEY}"},
                timeout=(5, 30), stream=True, allow_redirects=False) as response:
            if response.status_code in (400, 422):
                data = fal.read_json(response, allow_errors=True)
                state = "COMPLETED" if fal.content_blocked(data) else None
            else:
                response.raise_for_status()
                state = fal.read_json(response).get("status")
        if state == "COMPLETED":
            StoryWorkSlot.objects.filter(pk=slot.pk, job_id=slot.job_id, token=slot.token,
                request_id=slot.request_id).update(**EMPTY)
    except (requests.RequestException, ValueError):
        log.warning("story_failed_request_drain_delayed slot=%s", slot.pk)
    return True
