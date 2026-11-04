"""Small private result previews, produced by workers, never by project-list requests."""
import logging
import shutil
import subprocess
import tempfile
import time
from datetime import timedelta
from pathlib import Path
from botocore.exceptions import BotoCoreError, ClientError

from django.conf import settings
from django.db import transaction
from django.db.models import Q
from django.utils import timezone

from . import storage
from .models import Asset

log = logging.getLogger(__name__)
MAX_PREVIEW = 256 * 1024


@transaction.atomic
def claim(identifier=None):
    now = timezone.now()
    queue = Asset.objects.select_for_update(skip_locked=True).filter(ready=True, deleted=False).filter(
        Q(thumbnail_state="pending", thumbnail_lease_until__isnull=True) |
        Q(thumbnail_state__in=("pending", "processing"), thumbnail_lease_until__lt=now))
    if identifier is not None:
        queue = queue.filter(pk=identifier)
    asset = queue.order_by("created_at", "pk").first()
    if asset is not None:
        asset.thumbnail_state = "processing"
        asset.thumbnail_lease_until = now + timedelta(minutes=3)
        asset.thumbnail_attempts += 1
        asset.save(update_fields=["thumbnail_state", "thumbnail_lease_until", "thumbnail_attempts"])
    return asset


def render(source, destination):
    # Local files only: the decoder cannot follow remote or embedded network URLs.
    subprocess.run([
        "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-max_alloc", "67108864",
        "-protocol_whitelist", "file,pipe", "-threads", "1", "-f", "mov" if source.suffix == ".mp4" else "image2", "-i", str(source),
        "-map", "0:v:0", "-frames:v", "1", "-an", "-sn",
        "-vf", "scale=480:480:force_original_aspect_ratio=decrease:force_divisible_by=2",
        "-threads", "1", "-filter_threads", "1", "-q:v", "4", "-f", "image2", "-y", str(destination),
    ], check=True, timeout=30, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def generate(identifier=None):
    asset = claim(identifier)
    if asset is None:
        return False
    lease = asset.thumbnail_lease_until
    try:
        storage.require_owner_key(asset)
        if asset.mime not in ("image/png", "image/jpeg", "image/webp", "video/mp4") or not 0 < asset.size <= settings.MAX_UPLOAD_BYTES:
            raise ValueError("Unsupported preview media")
        s3 = storage.client()
        suffix = ".mp4" if asset.mime == "video/mp4" else {"image/png": ".png", "image/jpeg": ".jpg", "image/webp": ".webp"}[asset.mime]
        with tempfile.TemporaryDirectory(prefix="motion-preview-") as folder:
            if shutil.disk_usage(folder).free < asset.size + 4 * 1024 * 1024:
                raise ValueError("Insufficient preview workspace")
            source, destination = Path(folder) / ("source" + suffix), Path(folder) / "preview.jpg"
            deadline, total = time.monotonic() + 45, 0
            response = s3.get_object(Bucket=settings.S3_BUCKET, Key=asset.key)
            with response["Body"] as stream, source.open("wb") as output:
                while chunk := stream.read(64 * 1024):
                    if not isinstance(chunk, bytes):
                        raise ValueError("Invalid preview input")
                    total += len(chunk)
                    if total > asset.size or time.monotonic() > deadline:
                        raise ValueError("Preview input exceeded its limit")
                    output.write(chunk)
            if total != asset.size:
                raise ValueError("Incomplete preview input")
            render(source, destination)
            if not 0 < destination.stat().st_size <= MAX_PREVIEW:
                raise ValueError("Preview exceeded its limit")
            image = destination.read_bytes()
            if not image.startswith(b"\xff\xd8\xff") or not image.endswith(b"\xff\xd9"):
                raise ValueError("Invalid preview")
            key = asset.key + ".thumbnail.jpg"
            s3.put_object(Bucket=settings.S3_BUCKET, Key=key, Body=image, ContentLength=len(image), ContentType="image/jpeg")
            Asset.objects.filter(pk=asset.pk, thumbnail_state="processing", thumbnail_lease_until=lease, deleted=False).update(
                thumbnail_key=key, thumbnail_size=len(image), thumbnail_state="ready", thumbnail_lease_until=None)
    except Exception as error:
        # A preview failure must never refund/restart an already successful paid generation.
        log.warning("generation_thumbnail_failed asset_id=%s error_type=%s", asset.pk, type(error).__name__)
        retry = asset.thumbnail_attempts < 3 and isinstance(error, (OSError, TimeoutError, subprocess.TimeoutExpired, BotoCoreError, ClientError))
        Asset.objects.filter(pk=asset.pk, thumbnail_state="processing", thumbnail_lease_until=lease).update(
            thumbnail_state="pending" if retry else "failed",
            thumbnail_lease_until=timezone.now() + timedelta(seconds=30 * asset.thumbnail_attempts) if retry else None)
    return True
