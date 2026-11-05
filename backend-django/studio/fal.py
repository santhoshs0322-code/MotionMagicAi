"""Durable asynchronous fal queue integration. Never retry a paid submission."""
import json
import re
import subprocess
from datetime import timedelta
from urllib.parse import urlsplit

import requests
from botocore.exceptions import BotoCoreError, ClientError
from django.conf import settings
from django.utils import timezone

from . import fal_catalog, storage, reference_media
from .models import Job, Asset
from .errors import Problem

QUEUE = "https://queue.fal.run"


def configured():
    return bool(settings.FAL_KEY and settings.S3_BUCKET)


def read_json(response):
    content = bytearray()
    for chunk in response.iter_content(64 * 1024):
        content.extend(chunk)
        if len(content) > 1024**2:
            raise ValueError("Provider response too large")
    data = json.loads(content)
    if not isinstance(data, dict):
        raise ValueError("Invalid provider response")
    return data


def terminal(job, output=None, rejected=False):
    # This is the configured conservative cost allowance, NOT a fal invoice amount.
    # Failures release the customer's entire hold; ambiguous calls still consume budget.
    cost = job.provider_context.get("verified_cost", job.quote.provider_cost_paise)
    if type(cost) is not int or not 0 <= cost <= job.quote.provider_cost_paise:
        cost = job.quote.provider_cost_paise
    result = {"state": "succeeded" if output else "failed", "currency": "INR",
              "cost_paise": 0 if rejected else cost}
    if output:
        result["output"] = output
    return result


def submit(job):
    try:
        source_url = None
        reference_urls = []
        payload = job.quote.payload
        if payload.get("input_key"):
            asset = job.account.asset_set.get(pk=payload["asset_id"], key=payload["input_key"], ready=True, deleted=False)
            reference_video = bool(payload.get("config", {}).get("reference_video"))
            valid_media = (asset.mime == "video/mp4" and asset.size <= 200 * 1024**2) if reference_video else (
                asset.mime in {"image/png", "image/jpeg", "image/webp"} and asset.size <= settings.GEMINI_MAX_INPUT_BYTES)
            if not valid_media:
                return terminal(job, rejected=True)
            if reference_video:
                source_url, cost = reference_media.prepare_video(job, asset)
                job.provider_context = {**job.provider_context, "verified_cost": cost}
                if not Job.objects.filter(pk=job.pk, lease_id=job.lease_id).update(provider_context=job.provider_context):
                    raise ValueError("Worker lease was lost")
            else:
                storage.require_owner_key(asset)
                source_url = storage.client().generate_presigned_url("get_object", Params={"Bucket": settings.S3_BUCKET, "Key": asset.key}, ExpiresIn=86400)
        images = payload.get("reference_images", [])
        if len(images) != payload.get("reference_image_count", 0) or len(images) > payload["config"].get("max_reference_images", 0):
            raise ValueError("Invalid reference photos")
        for row in images:
            asset = job.account.asset_set.get(pk=row["id"], key=row["key"], ready=True, deleted=False)
            storage.require_owner_key(asset)
            if asset.mime not in {"image/png", "image/jpeg", "image/webp"} or not 0 < asset.size <= settings.GEMINI_MAX_INPUT_BYTES:
                raise ValueError("Invalid reference photo")
            reference_urls.append(storage.client().generate_presigned_url("get_object", Params={"Bucket": settings.S3_BUCKET, "Key": asset.key}, ExpiresIn=86400))
        endpoint, body = fal_catalog.request(payload, source_url, reference_urls)
    except (ValueError, KeyError, OSError, subprocess.SubprocessError, Problem, Asset.DoesNotExist):
        return {**terminal(job, rejected=True), "error_code": "invalid_reference"}
    except (BotoCoreError, ClientError):
        # Nothing has been submitted to fal. A storage outage must not leave a hold.
        return terminal(job, rejected=True)
    try:
        with requests.post(f"{QUEUE}/{endpoint}", json=body, headers={"Authorization": f"Key {settings.FAL_KEY}"},
                           timeout=(5, 30), allow_redirects=False, stream=True) as response:
            if response.status_code not in (200, 201, 202):
                return terminal(job, rejected=response.status_code in (400, 401, 403, 404, 422, 429))
            data = read_json(response)
        identifier = data.get("request_id", "")
        if not isinstance(identifier, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,160}", identifier):
            return terminal(job)
        # Save before any result fetch. Reconciliation uses this ID after a worker restart.
        root = "/".join(endpoint.split("/")[:2])
        saved = Job.objects.filter(pk=job.pk, lease_id=job.lease_id).update(provider_id=identifier, provider_context={**job.provider_context, "root": root, "endpoint": endpoint})
        if not saved:
            raise ValueError("Worker lease was lost")
        return {"state": "running", "id": identifier}
    except requests.RequestException:
        return terminal(job)


def media_url(value):
    url = urlsplit(value)
    host = url.hostname or ""
    allowed = host == "fal.media" or host.endswith(".fal.media") or (host == "storage.googleapis.com" and url.path.startswith("/falserverless/"))
    if url.scheme != "https" or not allowed or url.username or url.password or url.port not in (None, 443):
        raise ValueError("Untrusted provider media URL")
    return value


def download(job, data):
    if any(data.get("has_nsfw_concepts", [])):
        return terminal(job)
    video = job.quote.payload["kind"] == "video"
    output = data.get("video") if video else (data.get("images") or [None])[0]
    if not isinstance(output, dict):
        return terminal(job)
    url = media_url(output["url"])
    # Credentials must NEVER be sent to the media host. Redirects are deliberately refused.
    with requests.get(url, stream=True, timeout=(5, 60), allow_redirects=False) as response:
        response.raise_for_status()
        if response.status_code != 200:
            raise ValueError("Unexpected media redirect")
        mime = response.headers.get("Content-Type", "").split(";")[0].lower()
        if mime == "application/octet-stream":
            mime = output.get("content_type", "video/mp4" if video else "")
        if mime not in ({"video/mp4"} if video else {"image/png", "image/jpeg", "image/webp"}):
            raise ValueError("Unsupported provider media")
        size = response.headers.get("Content-Length")
        limit = settings.GEMINI_MAX_VIDEO_BYTES if video else settings.GEMINI_MAX_OUTPUT_BYTES
        stored = storage.put_generated_stream(job, response.raw, mime, limit, int(size) if size else None)
    return terminal(job, stored)


def reconcile(job):
    video = job.quote.payload["kind"] == "video"
    existing = storage.existing_generated_video(job) if video else storage.existing_generated_image(job)
    if existing:
        return terminal(job, existing)
    # A crash between POST and saving its ID is ambiguous: refund, never duplicate it.
    if not job.provider_id or job.created_at < timezone.now() - timedelta(hours=24):
        return terminal(job)
    root = job.provider_context.get("root")
    allowed = {"fal-ai/kling-video", "fal-ai/pixverse", "fal-ai/qwen-image", "fal-ai/qwen-image-edit", "fal-ai/nano-banana", "fal-ai/bytedance", "openai/gpt-image-2.5", "openai/gpt-image-2", "bytedance/seedream"}
    if root not in allowed or not re.fullmatch(r"[A-Za-z0-9_-]{1,160}", job.provider_id):
        return terminal(job)
    base = f"{QUEUE}/{root}/requests/{job.provider_id}"
    headers = {"Authorization": f"Key {settings.FAL_KEY}"}
    with requests.get(base + "/status", headers=headers, timeout=(5, 30), stream=True, allow_redirects=False) as response:
        response.raise_for_status()
        state = read_json(response).get("status")
    if state in ("IN_QUEUE", "IN_PROGRESS"):
        return {"state": "running", "id": job.provider_id}
    if state != "COMPLETED":
        raise ValueError("Unknown provider queue status")
    with requests.get(base, headers=headers, timeout=(5, 30), stream=True, allow_redirects=False) as response:
        if response.status_code in (400, 422):
            return terminal(job)
        response.raise_for_status()
        result = read_json(response)
    if result.get("error") or result.get("detail"):
        return terminal(job)
    return download(job, result)
