import base64
import binascii
import json
from urllib.parse import quote

import requests
from django.conf import settings

from . import storage


MODELS = {
    "gemini-nano-banana-2.1",
    "gemini-3.1-flash-image",
    "gemini-3.1-flash-lite-image",
    "gemini-3-pro-image",
}
ASPECT_RATIOS = {"1:1", "2:3", "3:2", "3:4", "4:3", "4:5", "5:4", "9:16", "16:9", "21:9"}
IMAGE_SIZES = {"1K", "2K", "4K"}
OUTPUT_MIMES = {"image/png", "image/jpeg", "image/webp"}


def configured():
    return bool(settings.GEMINI_API_KEY and settings.S3_BUCKET)


def supports(model):
    return configured() and model in MODELS


def _read_input(key, mime):
    if mime not in OUTPUT_MIMES:
        raise ValueError("unsupported input type")
    result = storage.client().get_object(Bucket=settings.S3_BUCKET, Key=key)
    body = result["Body"]
    try:
        content = body.read(settings.GEMINI_MAX_INPUT_BYTES + 1)
    finally:
        body.close()
    if not content or len(content) > settings.GEMINI_MAX_INPUT_BYTES:
        raise ValueError("input image exceeded provider limit")
    return {"inlineData": {"mimeType": mime, "data": base64.b64encode(content).decode("ascii")}}


def _request_body(job):
    payload = job.quote.payload
    model = payload.get("model")
    config = payload.get("config", job.quote.rate.config)
    aspect_ratio = payload.get("aspect_ratio", "1:1")
    image_size = config.get("image_size", "1K")
    if model not in MODELS or aspect_ratio not in ASPECT_RATIOS or image_size not in IMAGE_SIZES:
        raise ValueError("unsupported Gemini configuration")
    parts = [{"text": payload["prompt"]}]
    if payload.get("input_key"):
        source = job.quote.payload.get("asset_id")
        asset = job.account.asset_set.get(pk=source, key=payload["input_key"], ready=True, deleted=False)
        parts.append(_read_input(asset.key, asset.mime))
    return {
        "contents": [{"role": "user", "parts": parts}],
        "generationConfig": {
            "responseModalities": ["TEXT", "IMAGE"],
            "imageConfig": {"aspectRatio": aspect_ratio, "imageSize": image_size},
        },
    }


def _decode_response(response):
    limit = (settings.GEMINI_MAX_OUTPUT_BYTES * 4 // 3) + 2 * 1024**2
    raw = bytearray()
    for chunk in response.iter_content(64 * 1024):
        raw.extend(chunk)
        if len(raw) > limit:
            raise ValueError("Gemini response exceeded limit")
    data = json.loads(raw)
    candidates = data.get("candidates")
    if not isinstance(candidates, list) or not candidates:
        raise ValueError("Gemini returned no candidate")
    parts = candidates[0].get("content", {}).get("parts", [])
    for part in parts:
        inline = part.get("inlineData") or part.get("inline_data") or {}
        mime = inline.get("mimeType") or inline.get("mime_type")
        encoded = inline.get("data")
        if mime in OUTPUT_MIMES and isinstance(encoded, str):
            try:
                content = base64.b64decode(encoded, validate=True)
            except (ValueError, binascii.Error):
                raise ValueError("Gemini returned invalid image data") from None
            if 0 < len(content) <= settings.GEMINI_MAX_OUTPUT_BYTES:
                return content, mime
    raise ValueError("Gemini returned no image")


def submit(job):
    # Gemini generateContent has no provider idempotency key. This method is called once;
    # any ambiguous outcome is settled as failed and never resubmitted automatically.
    try:
        model = job.quote.payload["model"]
        endpoint = f"{settings.GEMINI_BASE_URL}/models/{quote(model, safe='')}:generateContent"
        with requests.post(
            endpoint,
            json=_request_body(job),
            headers={"x-goog-api-key": settings.GEMINI_API_KEY, "Accept": "application/json"},
            timeout=(5, 180),
            allow_redirects=False,
            stream=True,
        ) as response:
            if response.status_code != 200:
                return _failed(job)
            content, mime = _decode_response(response)
        output = storage.put_generated_image(job, content, mime)
        return _succeeded(job, output)
    except Exception:
        # Do not leak provider messages/keys and do not risk a duplicate paid request.
        return _failed(job)


def reconcile(job):
    output = storage.existing_generated_image(job)
    return _succeeded(job, output) if output else _failed(job)


def _succeeded(job, output):
    return {"state": "succeeded", "cost_paise": job.quote.provider_cost_paise, "currency": "INR", "output": output}


def _failed(job):
    # Pessimistically count the reserved provider cost against the daily safety budget.
    return {"state": "failed", "cost_paise": job.quote.provider_cost_paise, "currency": "INR"}
