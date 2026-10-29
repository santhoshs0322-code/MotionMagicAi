import base64
import json
import re
from contextlib import ExitStack
from urllib.parse import urlparse

import requests
from django.conf import settings

from . import storage


MODELS = {"gemini-omni-1.1-flash"}
ASPECT_RATIOS = {"9:16", "16:9"}
RESOLUTIONS = {"720p", "1080p", "4k"}
FILE_ID = re.compile(r"(?:^|/)files/([A-Za-z0-9_-]+)(?::download)?(?:\?|$)")


def _json(response, limit=256 * 1024):
    raw = bytearray()
    for chunk in response.iter_content(16 * 1024):
        raw.extend(chunk)
        if len(raw) > limit:
            raise ValueError("provider response exceeded limit")
    value = json.loads(raw)
    if not isinstance(value, dict):
        raise ValueError("invalid provider response")
    return value


def _source_image(job):
    payload = job.quote.payload
    if not payload.get("input_key"):
        return None
    asset = job.account.asset_set.get(pk=payload["asset_id"], key=payload["input_key"], ready=True, deleted=False)
    if not asset.mime.startswith("image/"):
        raise ValueError("video generation requires image input")
    result = storage.client().get_object(Bucket=settings.S3_BUCKET, Key=asset.key)
    body = result["Body"]
    try:
        content = body.read(settings.GEMINI_MAX_INPUT_BYTES + 1)
    finally:
        body.close()
    if not content or len(content) > settings.GEMINI_MAX_INPUT_BYTES:
        raise ValueError("input image exceeded provider limit")
    return {"type": "image", "mime_type": asset.mime, "data": base64.b64encode(content).decode("ascii")}


def _body(job):
    payload = job.quote.payload
    config = job.quote.rate.config
    aspect_ratio = payload.get("aspect_ratio", "9:16")
    resolution = config.get("resolution", "720p")
    if aspect_ratio not in ASPECT_RATIOS or resolution not in RESOLUTIONS:
        raise ValueError("unsupported video configuration")
    prompt = f"Create an exactly {payload['units']}-second video. {payload['prompt']}"
    if not payload.get("generated_audio", True):
        prompt += " No dialogue, music, ambient sound, or sound effects; output a silent video."
    image = _source_image(job)
    input_value = [image, {"type": "text", "text": prompt}] if image else prompt
    return {
        "model": "gemini-omni-1.1-flash",
        "input": input_value,
        "response_format": {"type": "video", "delivery": "uri", "aspect_ratio": aspect_ratio, "resolution": resolution},
    }


def _video_uri(data):
    top = data.get("output_video") or {}
    if isinstance(top, dict) and isinstance(top.get("uri"), str):
        return top["uri"]
    for step in data.get("steps", []):
        if not isinstance(step, dict) or step.get("type") != "model_output":
            continue
        for content in step.get("content", []):
            if isinstance(content, dict) and content.get("type") == "video" and isinstance(content.get("uri"), str):
                return content["uri"]
    raise ValueError("provider returned no video URI")


def _file_id(uri):
    match = FILE_ID.search(uri)
    if not match or not uri.startswith("https://generativelanguage.googleapis.com/"):
        raise ValueError("invalid provider file URI")
    return match.group(1)


def submit(job):
    try:
        with requests.post(
            settings.GEMINI_INTERACTIONS_URL + "/interactions",
            json=_body(job),
            headers={"x-goog-api-key": settings.GEMINI_API_KEY, "Accept": "application/json"},
            timeout=(5, 300), allow_redirects=False, stream=True,
        ) as response:
            if response.status_code != 200:
                return _failed(job)
            identifier = _file_id(_video_uri(_json(response)))
        return {"state": "running", "id": identifier}
    except Exception:
        return _failed(job)


def reconcile(job):
    existing = storage.existing_generated_video(job)
    if existing:
        return _succeeded(job, existing)
    if not job.provider_id or not re.fullmatch(r"[A-Za-z0-9_-]+", job.provider_id):
        return _failed(job)
    try:
        headers = {"x-goog-api-key": settings.GEMINI_API_KEY, "Accept": "application/json"}
        with requests.get(f"{settings.GEMINI_INTERACTIONS_URL}/files/{job.provider_id}", headers=headers, timeout=(5, 20), allow_redirects=False, stream=True) as response:
            if response.status_code != 200:
                return _failed(job)
            state = _json(response).get("state")
        if state == "FAILED":
            return _failed(job)
        if state != "ACTIVE":
            return {"state": "running", "id": job.provider_id}
        with ExitStack() as stack:
            response = stack.enter_context(requests.get(f"{settings.GEMINI_INTERACTIONS_URL}/files/{job.provider_id}:download?alt=media", headers={"x-goog-api-key": settings.GEMINI_API_KEY}, timeout=(5, 600), allow_redirects=False, stream=True))
            if response.status_code in (301, 302, 303, 307, 308):
                location = response.headers.get("Location", "")
                parsed = urlparse(location)
                if parsed.scheme != "https" or not (parsed.hostname == "generativelanguage.googleapis.com" or (parsed.hostname or "").endswith(".googleusercontent.com")):
                    return _failed(job)
                # Signed media redirects must never receive the Gemini API key.
                response = stack.enter_context(requests.get(location, timeout=(5, 600), allow_redirects=False, stream=True))
            if response.status_code != 200:
                return _failed(job)
            length = response.headers.get("Content-Length")
            declared = int(length) if length and length.isdigit() else None
            output = storage.put_generated_stream(job, response.raw, "video/mp4", settings.GEMINI_MAX_VIDEO_BYTES, declared)
        return _succeeded(job, output)
    except Exception:
        return _failed(job)


def _succeeded(job, output):
    return {"state": "succeeded", "cost_paise": job.quote.provider_cost_paise, "currency": "INR", "output": output}


def _failed(job):
    return {"state": "failed", "cost_paise": job.quote.provider_cost_paise, "currency": "INR"}
