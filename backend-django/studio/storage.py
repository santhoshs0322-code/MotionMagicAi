import uuid
import boto3
from botocore.config import Config
from botocore.exceptions import ClientError
from boto3.s3.transfer import TransferConfig
from django.conf import settings
from .models import Asset
from .errors import Problem

MIMES = {"image/jpeg", "image/png", "image/webp", "video/mp4", "audio/mpeg", "audio/mp4", "audio/wav"}

def client():
    if not settings.S3_BUCKET:
        raise Problem("Cloud storage is not enabled.", 503)
    # Render uses prefixed server-only credentials; ECS can leave them blank and use its task role.
    credentials = {}
    if settings.AWS_ACCESS_KEY_ID:
        credentials = {
            "aws_access_key_id": settings.AWS_ACCESS_KEY_ID,
            "aws_secret_access_key": settings.AWS_SECRET_ACCESS_KEY,
        }
        if settings.AWS_SESSION_TOKEN:
            credentials["aws_session_token"] = settings.AWS_SESSION_TOKEN
    return boto3.client("s3", region_name=settings.AWS_REGION, config=Config(connect_timeout=3, read_timeout=20, retries={"max_attempts": 2}, signature_version="s3v4"), **credentials)

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

def generated_key(job, mime="image/png"):
    extension = {"image/png": "png", "image/jpeg": "jpg", "image/webp": "webp", "video/mp4": "mp4"}.get(mime)
    if not extension:
        raise Problem("Unsupported generated image type.", 503)
    return f"accounts/{job.account_id}/outputs/{job.pk}/generated.{extension}"

def put_generated_image(job, content, mime):
    if not isinstance(content, bytes) or not 0 < len(content) <= settings.GEMINI_MAX_OUTPUT_BYTES:
        raise Problem("Generated image exceeded the output limit.", 503)
    key = generated_key(job, mime)
    client().put_object(
        Bucket=settings.S3_BUCKET,
        Key=key,
        Body=content,
        ContentLength=len(content),
        ContentType=mime,
        Metadata={"motion-job": str(job.pk)},
    )
    return {"key": key, "mime": mime, "size": len(content)}

def existing_generated_image(job):
    for mime in ("image/png", "image/jpeg", "image/webp"):
        key = generated_key(job, mime)
        try:
            info = client().head_object(Bucket=settings.S3_BUCKET, Key=key)
        except ClientError as exc:
            if exc.response.get("Error", {}).get("Code") in ("404", "NoSuchKey", "NotFound"):
                continue
            raise
        size = info.get("ContentLength")
        if info.get("ContentType") == mime and type(size) is int and 0 < size <= settings.GEMINI_MAX_OUTPUT_BYTES:
            return {"key": key, "mime": mime, "size": size}
    return None

class LimitedReader:
    def __init__(self, source, limit):
        self.source = source
        self.limit = limit
        self.read_bytes = 0

    def read(self, size=-1):
        remaining = self.limit + 1 - self.read_bytes
        if remaining <= 0:
            raise ValueError("provider media exceeded limit")
        requested = remaining if size is None or size < 0 else min(size, remaining)
        chunk = self.source.read(requested)
        self.read_bytes += len(chunk)
        if self.read_bytes > self.limit:
            raise ValueError("provider media exceeded limit")
        return chunk

def put_generated_stream(job, source, mime, maximum_size, declared_size=None):
    if mime not in MIMES or type(maximum_size) is not int or maximum_size <= 0:
        raise Problem("Invalid generated media configuration.", 503)
    if declared_size is not None and (type(declared_size) is not int or not 0 < declared_size <= maximum_size):
        raise Problem("Generated media exceeded the output limit.", 503)
    key = generated_key(job, mime)
    reader = LimitedReader(source, maximum_size)
    client().upload_fileobj(
        reader,
        settings.S3_BUCKET,
        key,
        ExtraArgs={"ContentType": mime, "Metadata": {"motion-job": str(job.pk)}},
        Config=TransferConfig(multipart_threshold=8 * 1024**2, multipart_chunksize=8 * 1024**2, max_concurrency=2, use_threads=True),
    )
    info = client().head_object(Bucket=settings.S3_BUCKET, Key=key)
    size = info.get("ContentLength")
    if type(size) is not int or not 0 < size <= maximum_size or info.get("ContentType") != mime:
        raise Problem("Generated media upload could not be verified.", 503)
    return {"key": key, "mime": mime, "size": size}

def existing_generated_video(job):
    key = generated_key(job, "video/mp4")
    try:
        info = client().head_object(Bucket=settings.S3_BUCKET, Key=key)
    except ClientError as exc:
        if exc.response.get("Error", {}).get("Code") in ("404", "NoSuchKey", "NotFound"):
            return None
        raise
    size = info.get("ContentLength")
    if info.get("ContentType") == "video/mp4" and type(size) is int and 0 < size <= settings.GEMINI_MAX_VIDEO_BYTES:
        return {"key": key, "mime": "video/mp4", "size": size}
    return None
