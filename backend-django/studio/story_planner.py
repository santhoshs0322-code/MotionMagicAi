"""OpenAI background Responses: bounded structured screenplay, key stays server-side."""
import re
import requests
from django.conf import settings
from . import fal

URL = "https://api.openai.com/v1/responses"


def body(job, instructions):
    count = job.quote.payload["units"] // 15
    return dict(model=job.quote.payload["config"]["planner_model"], background=True, store=True,
        max_output_tokens=12000, reasoning={"effort": "none"},
        instructions=instructions["system_prompt"], input=instructions["prompt"],
        text={"format": {"type": "json_schema", "name": "motion_story", "strict": True,
            "schema": {"type": "object", "additionalProperties": False, "required": ["scenes"],
                "properties": {"scenes": {"type": "array", "minItems": count, "maxItems": count,
                    "items": {"type": "object", "additionalProperties": False, "required": ["prompt"],
                        "properties": {"prompt": {"type": "string", "minLength": 20, "maxLength": 2200}}}}}}}})


def poll(identifier):
    if not re.fullmatch(r"resp_[A-Za-z0-9_-]{1,150}", identifier):
        return {"state": "failed"}
    with requests.get(f"{URL}/{identifier}", headers={"Authorization": f"Bearer {settings.OPENAI_API_KEY}"},
            timeout=(5, 30), stream=True, allow_redirects=False) as response:
        if response.status_code in (400, 404, 410, 422):
            return {"state": "failed"}
        response.raise_for_status()
        data = fal.read_json(response)
    if data.get("status") in ("queued", "in_progress"):
        return {"state": "running"}
    if data.get("status") != "completed":
        return {"state": "failed"}
    parts = [part for item in data.get("output", []) if item.get("type") == "message"
             for part in item.get("content", [])]
    if any(part.get("type") == "refusal" for part in parts):
        return {"state": "failed", "error_code": "content_policy_violation"}
    text = "".join(part.get("text", "") for part in parts if part.get("type") == "output_text")
    usage = data.get("usage", {})
    if not isinstance(usage, dict):
        usage = {}
    # Only bounded numeric accounting data; no complete raw API response or secrets.
    usage = {key: value for key, value in usage.items() if key in ("input_tokens", "output_tokens", "total_tokens")
             and type(value) is int and 0 <= value <= 1000000}
    return {"state": "completed", "text": text, "usage": usage}
