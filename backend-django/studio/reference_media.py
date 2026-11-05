"""Worker-only validation. Never decode a video in a quote/history HTTP request."""
import json
import shutil
import subprocess
import tempfile
import time
from decimal import Decimal, InvalidOperation, ROUND_CEILING
from pathlib import Path

from boto3.s3.transfer import TransferConfig
from django.conf import settings

from . import storage


def billable_seconds(path):
    with tempfile.TemporaryFile() as output:
        subprocess.run([
            "ffprobe", "-v", "error", "-max_alloc", "67108864", "-protocol_whitelist", "file,pipe",
            "-probesize", "16777216", "-analyzeduration", "10000000", "-f", "mov", "-select_streams", "v:0",
            "-show_entries", "format=duration:stream=width,height,duration", "-of", "json", str(path),
        ], check=True, timeout=30, stdin=subprocess.DEVNULL, stdout=output, stderr=subprocess.DEVNULL)
        if output.tell() > 16384:
            raise ValueError("Invalid video metadata")
        output.seek(0)
        data = json.load(output)
    try:
        stream = data["streams"][0]
        duration = max(Decimal(str(data["format"]["duration"])), Decimal(str(stream.get("duration", "0"))))
        if not duration.is_finite() or not Decimal(3) <= duration <= Decimal(15):
            raise ValueError("Reference must be 3–15 seconds")
        if any(not 720 <= int(stream[edge]) <= 3840 for edge in ("width", "height")):
            raise ValueError("Reference dimensions must be 720–3840 pixels")
        return int(duration.to_integral_value(rounding=ROUND_CEILING))
    except (KeyError, IndexError, TypeError, InvalidOperation) as error:
        raise ValueError("Invalid video metadata") from error


def prepare_video(job, asset):
    storage.require_owner_key(asset)
    if asset.account_id != job.account_id or asset.mime != "video/mp4" or not 0 < asset.size <= 200 * 1024**2:
        raise ValueError("Invalid reference video")
    s3 = storage.client()
    with tempfile.TemporaryDirectory(prefix="motion-reference-") as folder:
        if shutil.disk_usage(folder).free < asset.size + 8 * 1024**2:
            raise ValueError("Insufficient reference workspace")
        path = Path(folder) / "source.mp4"
        response = s3.get_object(Bucket=settings.S3_BUCKET, Key=asset.key)
        total, deadline = 0, time.monotonic() + 180
        with response["Body"] as source, path.open("wb") as output:
            while chunk := source.read(64 * 1024):
                total += len(chunk)
                if total > asset.size or time.monotonic() > deadline:
                    raise ValueError("Reference exceeds its upload limit")
                output.write(chunk)
        if total != asset.size:
            raise ValueError("Incomplete reference upload")
        seconds = billable_seconds(path)
        source_priced = job.quote.payload["config"].get("duration_from_source", False)
        if source_priced and seconds > job.quote.payload["units"]:
            raise ValueError("Reference exceeds the reserved duration")
        # The original upload URL may still be valid. fal must fetch the exact
        # bytes we checked, from a key that the customer can never overwrite.
        key = f"accounts/{job.account_id}/video/job-inputs/{job.pk}/source.mp4"
        s3.upload_file(str(path), settings.S3_BUCKET, key, ExtraArgs={"ContentType": "video/mp4"},
                       Config=TransferConfig(max_concurrency=1, use_threads=False))
    cost = job.quote.provider_cost_paise
    if source_priced:
        cost = cost // job.quote.payload["units"] * seconds
    return s3.generate_presigned_url("get_object", Params={"Bucket": settings.S3_BUCKET, "Key": key}, ExpiresIn=86400), cost
