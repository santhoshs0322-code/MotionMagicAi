"""Normalize one clip at a time; bounded RAM, local files only, no phone rendering."""
import json
import shutil
import subprocess
import tempfile
import time
from pathlib import Path

from django.conf import settings
from . import storage
from boto3.s3.transfer import TransferConfig


def download(s3, job, item, target, deadline=None):
    prefixes = (f"accounts/{job.account_id}/video/generated/", f"accounts/{job.account_id}/video/story-parts/{job.pk}/")
    if not item["key"].startswith(prefixes) or ".." in item["key"] or item["mime"] != "video/mp4":
        raise ValueError("Invalid story scene ownership")
    if not 0 < item["size"] <= settings.GEMINI_MAX_VIDEO_BYTES:
        raise ValueError("Invalid scene size")
    response = s3.get_object(Bucket=settings.S3_BUCKET, Key=item["key"])
    total = 0
    deadline = min(deadline or float("inf"), time.monotonic() + 300)
    with response["Body"] as source, target.open("wb") as dest:
        while chunk := source.read(64 * 1024):
            total += len(chunk)
            if total > item["size"] or time.monotonic() > deadline:
                raise ValueError("Story scene transfer exceeded its limit")
            dest.write(chunk)
    if total != item["size"]:
        raise ValueError("Incomplete story scene")


def run(args, timeout):
    subprocess.run(args, check=True, timeout=timeout, stdin=subprocess.DEVNULL,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def assemble(job, scenes):
    if len(scenes) != job.quote.payload["units"] // 15 or not 4 <= len(scenes) <= 20:
        raise ValueError("Incomplete story")
    ratio = job.quote.payload["aspect_ratio"]
    width, height = {"9:16": (1080, 1920), "16:9": (1920, 1080), "1:1": (1080, 1080)}[ratio]
    with tempfile.TemporaryDirectory(prefix="motion-film-") as folder:
        folder = Path(folder)
        # At most ~400 MB normalized output + source clip + final copy; fail before work.
        if shutil.disk_usage(folder).free < 2 * 1024**3:
            raise OSError("Free at least 2 GB for story assembly")
        files = []
        deadline = time.monotonic() + max(30, min(600, settings.GENERATION_LEASE_SECONDS - 360))
        s3 = storage.client()
        normalized = job.provider_context.setdefault("normalized", [])
        if len(normalized) < len(scenes):
            index = len(normalized)
            scene = scenes[index]
            source, target = folder / "source.mp4", folder / f"scene-{index:02d}.mp4"
            download(s3, job, scene, source, deadline)
            # Never silently publish a dialogue story without its audio track.
            check = subprocess.run(["ffprobe", "-v", "error", "-protocol_whitelist", "file,pipe",
                "-show_entries", "stream=codec_type:format=duration", "-of", "json", str(source)],
                check=True, timeout=20, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
            metadata = json.loads(check.stdout)
            if not any(row.get("codec_type") == "audio" for row in metadata.get("streams", [])):
                raise ValueError("A story scene is missing generated dialogue")
            if not 14.75 <= float(metadata.get("format", {}).get("duration", 0)) <= 16:
                raise ValueError("Story scene duration differs from the quoted film")
            run(["ffmpeg", "-v", "error", "-nostdin", "-y", "-threads", "1", "-filter_threads", "1",
                 "-protocol_whitelist", "file,pipe", "-i", str(source), "-map", "0:v:0", "-map", "0:a:0",
                 "-vf", f"scale={width}:{height}:force_original_aspect_ratio=decrease,pad={width}:{height}:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=24,tpad=stop_mode=clone:stop_duration=1",
                 "-af", "apad,aresample=48000", "-t", "15", "-c:v", "libx264", "-preset", "veryfast",
                 "-pix_fmt", "yuv420p", "-b:v", "4M", "-maxrate", "5M", "-bufsize", "8M",
                 "-c:a", "aac", "-b:a", "128k", "-ac", "2", "-threads", "1", str(target)], 240)
            key = f"accounts/{job.account_id}/video/story-parts/{job.pk}/{index:02d}.mp4"
            s3.upload_file(str(target), settings.S3_BUCKET, key, ExtraArgs={"ContentType": "video/mp4"},
                           Config=TransferConfig(max_concurrency=1, use_threads=False))
            info = s3.head_object(Bucket=settings.S3_BUCKET, Key=key)
            if info.get("ContentLength") != target.stat().st_size:
                raise ValueError("Incomplete normalized story scene")
            normalized.append(dict(key=key, size=target.stat().st_size, mime="video/mp4"))
            from .long_story import save
            save(job)
            return None  # One encode per lease, then yield to other jobs.
        for index, scene in enumerate(normalized):
            target = folder / f"scene-{index:02d}.mp4"
            download(s3, job, scene, target, deadline)
            files.append(target)
        manifest = folder / "clips.txt"
        manifest.write_text("\n".join(f"file '{path.name}'" for path in files), encoding="utf-8")
        result = folder / "film.mp4"
        run(["ffmpeg", "-v", "error", "-nostdin", "-y", "-protocol_whitelist", "file,pipe",
             "-f", "concat", "-safe", "1", "-i", str(manifest), "-t", str(job.quote.payload["units"]), "-c", "copy", "-movflags", "+faststart", str(result)], 120)
        with result.open("rb") as source:
            return storage.put_generated_stream(job, source, "video/mp4", settings.GEMINI_MAX_VIDEO_BYTES, result.stat().st_size)
