from . import gateway, gemini, gemini_video, fal, fal_catalog


def submit(job):
    if job.quote.payload.get("model") in fal_catalog.PROVIDER_MODELS:
        return fal.submit(job)
    if job.quote.payload.get("model") in gemini_video.MODELS:
        return gemini_video.submit(job)
    if job.quote.payload.get("model") in gemini.MODELS:
        return gemini.submit(job)
    return gateway.submit(job)


def reconcile(job):
    if job.quote.payload.get("model") in fal_catalog.PROVIDER_MODELS:
        return fal.reconcile(job)
    if job.quote.payload.get("model") in gemini_video.MODELS:
        return gemini_video.reconcile(job)
    if job.quote.payload.get("model") in gemini.MODELS:
        return gemini.reconcile(job)
    return gateway.reconcile(job)


def configured():
    from django.conf import settings
    return fal.configured() or gemini.configured() or bool(settings.GATEWAY_URL and settings.GATEWAY_TOKEN and settings.S3_BUCKET)
