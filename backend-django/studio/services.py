import json
import uuid
from datetime import timedelta
from django.conf import settings
from django.db import transaction
from django.db.models import Q
from django.utils import timezone
from .authentication import digest
from .errors import Problem
from .models import Wallet, Ledger, Rate, Quote, Job, Budget, Asset
from . import gemini, gemini_video, fal, fal_catalog

TERMINAL = ("succeeded", "failed", "cancelled")

def price(cost_paise, markup_bps=None, fee_bps=None):
    markup = settings.MARKUP_BPS if markup_bps is None else markup_bps
    fee = settings.STORE_FEE_BPS if fee_bps is None else fee_bps
    if type(cost_paise) is not int or cost_paise < 0 or not 0 <= fee < 10000 or markup < 0:
        raise Problem("Invalid price configuration.", 503)
    numerator = cost_paise * (10000 + markup)
    return (numerator + 10000 - fee - 1) // (10000 - fee)

def enabled_rates():
    if not settings.S3_BUCKET or settings.DAILY_PROVIDER_BUDGET_PAISE <= 0:
        return Rate.objects.none()
    providers = Q(pk__in=[])
    if settings.FAL_ONLY:
        return Rate.objects.filter(provider_model__in=fal_catalog.LAUNCH_MODELS, enabled=True, unit_cost_paise__gt=0, max_units__gt=0) if fal.configured() else Rate.objects.none()
    if settings.GATEWAY_URL and settings.GATEWAY_TOKEN:
        providers |= ~Q(provider_model__in=gemini.MODELS | gemini_video.MODELS | fal_catalog.PROVIDER_MODELS)
    if gemini.configured():
        providers |= Q(provider_model__in=gemini.MODELS | gemini_video.MODELS)
    if fal.configured():
        providers |= Q(provider_model__in=fal_catalog.PROVIDER_MODELS)
    return Rate.objects.filter(providers, enabled=True, unit_cost_paise__gt=0, max_units__gt=0)

def wallet_for_update(account):
    Wallet.objects.get_or_create(account=account)
    return Wallet.objects.select_for_update().get(account=account)

def entry(wallet, kind, reference, balance_delta=0, held_delta=0):
    # Caller holds the account's wallet lock within the same transaction.
    if Ledger.objects.filter(reference=reference).exists():
        return
    if wallet.held_paise + held_delta < 0:
        raise Problem("Invalid reservation state.", 503)
    wallet.balance_paise += balance_delta
    wallet.held_paise += held_delta
    wallet.save(update_fields=["balance_paise", "held_paise"])
    Ledger.objects.create(account_id=wallet.account_id, kind=kind, reference=reference, balance_delta=balance_delta, held_delta=held_delta, balance_after=wallet.balance_paise, held_after=wallet.held_paise)

def create_quote(account, data):
    if not isinstance(data, dict) or set(data) - {"service", "prompt", "duration", "asset_id", "aspect_ratio", "generated_audio"}:
        raise Problem("Use service, prompt, optional duration, asset_id, aspect_ratio and generated_audio only.")
    try:
        rate = enabled_rates().get(pk=data.get("service"))
    except Rate.DoesNotExist:
        raise Problem("This service is not available.", 409) from None
    prompt = data.get("prompt", "")
    if not isinstance(prompt, str) or not 1 <= len(prompt.strip()) <= (5000 if rate.kind == "voice" else 500):
        raise Problem("Enter a prompt within the service limit.")
    duration = data.get("duration", 5)
    rate_config = rate.config if isinstance(rate.config, dict) else {}
    supported_durations = rate_config.get("durations", [5, 10, 15])
    if rate.kind == "video" and (type(duration) is not int or not isinstance(supported_durations, list) or duration not in supported_durations):
        raise Problem("Choose a supported duration (5, 10 or 15 seconds).")
    units = duration if rate.kind == "video" else (len(prompt) + 999) // 1000 if rate.kind == "voice" else 1
    if units > rate.max_units:
        raise Problem("Requested generation exceeds this service's limit.")
    payload = {"service": rate.pk, "kind": rate.kind, "model": rate.provider_model, "prompt": prompt.strip(), "units": units, "revision": rate.revision, "config": rate_config}
    if rate.kind in ("image", "video"):
        default_ratio = "1:1" if rate.kind == "image" else "9:16"
        aspect_ratio = data.get("aspect_ratio", default_ratio)
        defaults = sorted(gemini.ASPECT_RATIOS) if rate.kind == "image" else sorted(gemini_video.ASPECT_RATIOS)
        supported_ratios = rate_config.get("aspect_ratios", defaults)
        if not isinstance(supported_ratios, list) or aspect_ratio not in supported_ratios or aspect_ratio not in gemini.ASPECT_RATIOS:
            raise Problem("Choose a supported aspect ratio.")
        payload["aspect_ratio"] = aspect_ratio
    elif "aspect_ratio" in data:
        raise Problem("Aspect ratio is not available for this service.")
    if rate.kind == "video":
        generated_audio = data.get("generated_audio", rate_config.get("generated_audio", True))
        if type(generated_audio) is not bool:
            raise Problem("generated_audio must be true or false.")
        if rate.provider_model in fal_catalog.PROVIDER_MODELS and generated_audio is not rate_config.get("generated_audio"):
            raise Problem("Choose the rate matching your audio setting.")
        payload["generated_audio"] = generated_audio
    elif "generated_audio" in data:
        raise Problem("Generated audio is available for video generation only.")
    if data.get("asset_id"):
        try:
            asset = Asset.objects.get(pk=uuid.UUID(str(data["asset_id"])), account=account, ready=True, deleted=False)
        except (ValueError, Asset.DoesNotExist):
            raise Problem("Source media is unavailable.", 404) from None
        if rate.kind == "voice" or not asset.mime.startswith("image/"):
            raise Problem("This generation only accepts image input.")
        if asset.size > settings.GEMINI_MAX_INPUT_BYTES:
            raise Problem("Source image is too large for this model. Choose a smaller photo.")
        payload["input_key"] = asset.key
        payload["asset_id"] = str(asset.pk)
    cost = rate.unit_cost_paise * units
    charge = price(cost + rate.overhead_paise)
    return Quote.objects.create(account=account, rate=rate, payload=payload, payload_hash=digest(json.dumps(payload, sort_keys=True)), provider_cost_paise=cost, overhead_paise=rate.overhead_paise, markup_bps=settings.MARKUP_BPS, fee_bps=settings.STORE_FEE_BPS, charge_paise=charge, expires_at=timezone.now() + timedelta(minutes=5))

@transaction.atomic
def submit_job(account, quote_id, key):
    if not isinstance(key, str) or not 8 <= len(key) <= 128 or not all(c.isalnum() or c in "-_" for c in key):
        raise Problem("Send an Idempotency-Key of 8–128 letters, digits, hyphens or underscores.")
    wallet = wallet_for_update(account)
    existing = Job.objects.filter(account=account, idempotency_key=key).first()
    if existing:
        if str(existing.quote_id) != str(quote_id):
            raise Problem("Idempotency key was used for a different quote.", 409)
        return existing, False
    try:
        quote = Quote.objects.select_related("rate").get(pk=uuid.UUID(str(quote_id)), account=account)
    except (ValueError, Quote.DoesNotExist):
        raise Problem("Quote not found.", 404) from None
    if quote.expires_at <= timezone.now() or not enabled_rates().filter(pk=quote.rate_id).exists() or quote.rate.revision != quote.payload["revision"]:
        raise Problem("Quote expired or service changed. Request another quote.", 409)
    if Job.objects.filter(quote=quote).exists():
        raise Problem("This quote already has a job.", 409)
    if wallet.available_paise < quote.charge_paise:
        raise Problem("Insufficient credits.", 402)
    if Job.objects.filter(account=account).exclude(state__in=TERMINAL).count() >= settings.MAX_ACTIVE_JOBS:
        raise Problem("Finish or cancel an existing generation first.", 429)
    shard = int(digest(str(account.pk))[:8], 16) % settings.BUDGET_SHARDS
    bucket, _ = Budget.objects.get_or_create(day=timezone.now().date(), shard=shard)
    bucket = Budget.objects.select_for_update().get(pk=bucket.pk)
    # Hard partition: sum of all shard allowances never exceeds the global daily ceiling.
    allowance = settings.DAILY_PROVIDER_BUDGET_PAISE // settings.BUDGET_SHARDS
    if bucket.reserved_paise + bucket.spent_paise + quote.provider_cost_paise > allowance:
        raise Problem("Generation capacity is temporarily full. Try later.", 429)
    bucket.reserved_paise += quote.provider_cost_paise
    bucket.save(update_fields=["reserved_paise"])
    job = Job.objects.create(account=account, quote=quote, idempotency_key=key, budget=bucket, next_attempt_at=timezone.now())
    entry(wallet, "reserve", f"job:{job.pk}:reserve", held_delta=quote.charge_paise)
    # The committed row IS the durable queue; no DB/message-broker dual-write gap.
    return job, True

@transaction.atomic
def finish_job(job_id, state, actual_cost=0, asset=None, error="", lease_id=None):
    # Consistent lock order: wallet, job, budget. Same order as submission/cancellation.
    snapshot = Job.objects.get(pk=job_id)
    wallet = wallet_for_update(snapshot.account)
    job = Job.objects.select_for_update().select_related("quote").get(pk=job_id)
    if job.state in TERMINAL:
        return job
    if lease_id is not None and job.lease_id != lease_id:
        return job
    if state not in TERMINAL or type(actual_cost) is not int or actual_cost < 0:
        raise Problem("Invalid job settlement.", 503)
    bucket = Budget.objects.select_for_update().get(pk=job.budget_id)
    bucket.reserved_paise -= job.quote.provider_cost_paise
    bucket.spent_paise += actual_cost
    bucket.save(update_fields=["reserved_paise", "spent_paise"])
    charged = min(job.quote.charge_paise, price(actual_cost + job.quote.overhead_paise, job.quote.markup_bps, job.quote.fee_bps)) if state == "succeeded" else 0
    entry(wallet, "charge" if charged else "release", f"job:{job.pk}:settle", balance_delta=-charged, held_delta=-job.quote.charge_paise)
    job.state, job.actual_cost_paise, job.charged_paise = state, actual_cost, charged
    job.output, job.error_code = asset, error
    job.lease_id, job.lease_until = None, None
    job.settled_at = timezone.now()
    job.save()
    return job

@transaction.atomic
def cancel_job(account, job_id):
    wallet_for_update(account)
    try:
        job = Job.objects.select_for_update().get(pk=job_id, account=account)
    except Job.DoesNotExist:
        raise Problem("Job not found.", 404) from None
    if job.state in TERMINAL:
        return job
    if job.state != "queued":
        raise Problem("Generation has reached the provider; cancellation is no longer guaranteed.", 409)
    return finish_job(job.pk, "cancelled")
