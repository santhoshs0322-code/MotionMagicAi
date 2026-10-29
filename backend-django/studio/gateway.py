import json
import requests
from django.conf import settings
from .errors import Problem

def request(method, path, body=None, key=None):
    if not settings.GATEWAY_URL:
        raise Problem("Generation is not configured.", 503)
    headers = {"Authorization": "Bearer " + settings.GATEWAY_TOKEN, "Accept": "application/json"}
    if key:
        headers["Idempotency-Key"] = key
    # No automatic POST retry. An ambiguous network failure is reconciled by key.
    with requests.request(method, settings.GATEWAY_URL + path, json=body, headers=headers, timeout=(3, 20), allow_redirects=False, stream=True) as response:
        if response.status_code == 404 and method == "GET":
            return {"state": "not_found"}
        if response.status_code not in (200, 201, 202):
            raise Problem("Generation provider is temporarily unavailable.", 503)
        result = bytearray()
        for chunk in response.iter_content(4096):
            result.extend(chunk)
            if len(result) > 65536:
                raise Problem("Provider response exceeded the size limit.", 503)
        value = json.loads(result)
        if not isinstance(value, dict) or value.get("state") not in ("queued", "running", "succeeded", "failed", "cancelled"):
            raise Problem("Invalid provider response.", 503)
        return value

def submit(job):
    return request("POST", "/jobs", {**job.quote.payload, "request_id": str(job.pk), "maximum_cost_paise": job.quote.provider_cost_paise, "currency": "INR", "output_bucket": settings.S3_BUCKET, "output_prefix": f"accounts/{job.account_id}/outputs/{job.pk}/"}, str(job.pk))

def reconcile(job):
    # The gateway contract requires this lookup to be authoritative and idempotent.
    return request("GET", "/jobs/by-key/" + str(job.pk))
