import uuid
import boto3
from botocore.config import Config
from django.conf import settings
from .models import Asset
from .errors import Problem

MIMES = {"image/jpeg", "image/png", "image/webp", "video/mp4", "audio/mpeg", "audio/mp4", "audio/wav"}

def client():
    if not settings.S3_BUCKET:
        raise Problem("Cloud storage is not enabled.", 503)
    # ECS task role supplies AWS credentials; never embed them in Android.
    return boto3.client("s3", region_name=settings.AWS_REGION, config=Config(connect_timeout=3, read_timeout=10, retries={"max_attempts": 2}, signature_version="s3v4"))

def begin_upload(account, mime, size):
    if mime not in MIMES or type(size) is not int or not 0 < size <= settings.MAX_UPLOAD_BYTES:
        raise Problem("Unsupported media type or file size.")
    identifier = uuid.uuid4()
    key = f"accounts/{account.pk}/uploads/{identifier}"
    asset = Asset.objects.create(id=identifier, account=account, key=key, mime=mime, size=size)
    try:
        form = client().generate_presigned_post(settings.S3_BUCKET, key, Fields={"Content-Type": mime}, Conditions=[{"Content-Type": mime}, ["content-length-range", size, size]], ExpiresIn=300)
    except Exception:
        asset.delete()
        raise
    return asset, form

def confirm_upload(asset):
    info = client().head_object(Bucket=settings.S3_BUCKET, Key=asset.key)
    if info["ContentLength"] != asset.size or info.get("ContentType") != asset.mime:
        raise Problem("Uploaded object does not match its declared type and size.")
    asset.ready = True
    asset.save(update_fields=["ready"])
    return asset

def download(asset):
    return client().generate_presigned_url("get_object", Params={"Bucket": settings.S3_BUCKET, "Key": asset.key, "ResponseContentDisposition": 'attachment; filename="motionmagic-media"'}, ExpiresIn=60)

def provider_output(job, data):
    # The gateway must put output under exactly this server-selected job prefix.
    prefix = f"accounts/{job.account_id}/outputs/{job.pk}/"
    key, mime, size = data.get("key"), data.get("mime"), data.get("size")
    if not isinstance(key, str) or not key.startswith(prefix) or ".." in key or len(key) > 300 or mime not in MIMES or type(size) is not int or not 0 < size <= settings.MAX_UPLOAD_BYTES:
        raise Problem("Invalid provider output.", 503)
    info = client().head_object(Bucket=settings.S3_BUCKET, Key=key)
    if info["ContentLength"] != size or info.get("ContentType") != mime:
        raise Problem("Provider output is not ready.", 503)
    asset, _ = Asset.objects.get_or_create(key=key, defaults={"account_id": job.account_id, "mime": mime, "size": size, "ready": True})
    return asset
