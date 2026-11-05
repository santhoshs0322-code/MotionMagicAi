import json
import re
import uuid
from collections import defaultdict, deque
from datetime import timedelta
from itertools import groupby
from django.conf import settings
from django.db import transaction
from django.db.models import Q
from django.utils import timezone
from .authentication import digest
from .errors import Problem
from .models import Wallet, Ledger, Rate, Quote, Job, Budget, Asset, Subscription, ShowcaseTemplate
from . import gemini, gemini_video, fal, fal_catalog

TERMINAL = ("succeeded", "failed", "cancelled")
ACTIVE_STATES = ("queued", "submitting", "running", "uncertain")
FREE_RATE_SLUGS = frozenset(("qwen-image-720p", "pixverse-v6-720p-silent"))

def minimum_plan(rate):
    if settings.CREDIT_ONLY_MODE:
        return "free"
    return "free" if rate.pk in FREE_RATE_SLUGS else "creator"

def require_rate_access(account, rate):
    if settings.CREDIT_ONLY_MODE:
        return
    if minimum_plan(rate) == "free":
        return
    if not Subscription.objects.filter(account=account, plan__in=("creator", "legend"),
                                       state__in=("active", "grace", "cancelled"),
                                       expires_at__gt=timezone.now()).exists():
        raise Problem("Upgrade to Creator or Legend to use this model and setting.", 403)

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
    wallet = Wallet.objects.select_for_update().get(account=account)
    if wallet.credit_buckets_version == 0:
        initialize_credit_buckets(wallet)
    expire_monthly(wallet)
    return wallet

def initialize_credit_buckets(wallet):
    """Classify legacy mixed balances once, under the wallet row lock.

    A deployment migration over every account would block Render startup. The
    historical ledger is replayed on each account's first wallet use instead.
    """
    rows = list(Ledger.objects.filter(account_id=wallet.account_id).order_by("created_at", "id"))
    free = wallet.balance_paise - sum(row.balance_delta for row in rows)
    # Some databases record several transactions in the same millisecond. The
    # ledger's before/after balances recover their order without guessing from
    # random UUIDs. Each historical row is visited a constant number of times.
    balance_cursor = free
    held_cursor = wallet.held_paise - sum(row.held_delta for row in rows)
    ordered_rows = []
    for _, same_time in groupby(rows, key=lambda row: row.created_at):
        candidates = defaultdict(deque)
        group = list(same_time)
        for row in group:
            candidates[(row.balance_after - row.balance_delta, row.held_after - row.held_delta)].append(row)
        for _ in group:
            choices = candidates.get((balance_cursor, held_cursor))
            if not choices:
                # Preserve timestamp order if a historical import did not
                # record a continuous balance chain.
                remaining = [row for queue in candidates.values() for row in queue]
                ordered_rows.extend(remaining)
                if remaining:
                    balance_cursor, held_cursor = remaining[-1].balance_after, remaining[-1].held_after
                break
            row = choices.popleft()
            ordered_rows.append(row)
            balance_cursor, held_cursor = row.balance_after, row.held_after
    monthly = topup = 0
    last_period = ""
    for row in ordered_rows:
        change = row.balance_delta
        if row.kind == "subscription" and change > 0:
            monthly = change  # Older unused period credits do not roll over.
            last_period = row.reference
        elif row.kind in ("purchase", "refund"):
            topup += change
        elif row.kind == "charge" and change < 0:
            spent = -change
            from_monthly = min(max(0, monthly), spent)
            monthly -= from_monthly
            spent -= from_monthly
            from_free = min(max(0, free), spent)
            free -= from_free
            topup -= spent - from_free
        elif row.kind == "monthly_expire":
            monthly = max(0, monthly + change)
        else:
            free += change
    current = Subscription.objects.filter(account_id=wallet.account_id, last_grant_key=last_period).order_by("-updated_at").first() if last_period else None
    expires_at = current.expires_at if current else None
    current_valid = current and current.state in ("active", "grace", "cancelled") and expires_at and expires_at > timezone.now()
    if not current_valid:
        # Keep only enough expired monthly credits to cover jobs already held.
        monthly = min(monthly, wallet.held_paise)
    reconstructed = free + monthly + topup
    if reconstructed > wallet.balance_paise:
        free -= reconstructed - wallet.balance_paise
        reconstructed = wallet.balance_paise
    wallet.monthly_balance_paise = max(0, monthly)
    wallet.monthly_period_key = last_period
    wallet.monthly_expires_at = expires_at
    wallet.topup_balance_paise = topup
    wallet.monthly_held_paise = wallet.topup_held_paise = 0
    held_left = wallet.held_paise
    free_held = 0
    for job in Job.objects.filter(account_id=wallet.account_id).exclude(state__in=TERMINAL).select_related("quote").order_by("created_at", "id"):
        held = min(job.quote.charge_paise, held_left)
        held_left -= held
        monthly_part = min(held, max(0, wallet.monthly_balance_paise - wallet.monthly_held_paise))
        free_part = min(held - monthly_part, max(0, free - free_held))
        topup_part = min(held - monthly_part - free_part, max(0, wallet.topup_balance_paise - wallet.topup_held_paise))
        free_held += held - monthly_part - topup_part
        wallet.monthly_held_paise += monthly_part
        wallet.topup_held_paise += topup_part
        job.monthly_reserved_paise = monthly_part
        job.topup_reserved_paise = topup_part
        job.monthly_period_key = last_period if monthly_part else ""
        job.save(update_fields=["monthly_reserved_paise", "topup_reserved_paise", "monthly_period_key"])
    previous_balance = wallet.balance_paise
    wallet.balance_paise = reconstructed
    wallet.credit_buckets_version = 1
    wallet.save(update_fields=["balance_paise", "monthly_balance_paise", "monthly_held_paise", "monthly_period_key", "monthly_expires_at",
                               "topup_balance_paise", "topup_held_paise", "credit_buckets_version"])
    if reconstructed != previous_balance:
        Ledger.objects.create(account_id=wallet.account_id, kind="monthly_expire", reference=f"bucket-migration:{wallet.account_id}",
                              balance_delta=reconstructed - previous_balance, held_delta=0,
                              balance_after=reconstructed, held_after=wallet.held_paise)

def expire_monthly(wallet, force=False):
    """Expire unspent period credits. Existing job holds settle separately."""
    if not wallet.monthly_period_key or not (force or (wallet.monthly_expires_at and wallet.monthly_expires_at <= timezone.now())):
        return
    unused = wallet.monthly_available_paise
    if unused > 0:
        entry(wallet, "monthly_expire", f"monthly-expire:{digest(wallet.monthly_period_key)}",
              balance_delta=-unused, monthly_balance_delta=-unused)

def grant_welcome_credits(account):
    """Grant the one-time verified-Google-account welcome balance.

    The account's unique Google subject and immutable ledger reference make this
    safe across login retries and concurrent web workers.
    """
    if not settings.WELCOME_CREDITS:
        return
    wallet = wallet_for_update(account)
    entry(wallet, "welcome", f"welcome:{account.pk}", balance_delta=settings.WELCOME_CREDITS)

def entry(wallet, kind, reference, balance_delta=0, held_delta=0,
          monthly_balance_delta=0, monthly_held_delta=0, topup_balance_delta=0, topup_held_delta=0):
    # Caller holds the account's wallet lock within the same transaction.
    if Ledger.objects.filter(reference=reference).exists():
        return
    if wallet.held_paise + held_delta < 0:
        raise Problem("Invalid reservation state.", 503)
    if (wallet.monthly_balance_paise + monthly_balance_delta < 0 or wallet.monthly_held_paise + monthly_held_delta < 0 or
            wallet.monthly_balance_paise + monthly_balance_delta < wallet.monthly_held_paise + monthly_held_delta or
            wallet.topup_held_paise + topup_held_delta < 0):
        raise Problem("Invalid credit bucket state.", 503)
    wallet.balance_paise += balance_delta
    wallet.held_paise += held_delta
    wallet.monthly_balance_paise += monthly_balance_delta
    wallet.monthly_held_paise += monthly_held_delta
    wallet.topup_balance_paise += topup_balance_delta
    wallet.topup_held_paise += topup_held_delta
    wallet.save(update_fields=["balance_paise", "held_paise", "monthly_balance_paise", "monthly_held_paise", "topup_balance_paise", "topup_held_paise"])
    Ledger.objects.create(account_id=wallet.account_id, kind=kind, reference=reference, balance_delta=balance_delta, held_delta=held_delta, balance_after=wallet.balance_paise, held_after=wallet.held_paise)

def source_asset(account, asset_id, kind, config):
    try:
        asset = Asset.objects.get(pk=uuid.UUID(str(asset_id)), account=account, ready=True, deleted=False)
    except (ValueError, Asset.DoesNotExist):
        raise Problem("Source media is unavailable.", 404) from None
    reference_video = bool(config.get("reference_video"))
    if kind == "voice" or (asset.mime != "video/mp4" if reference_video else not asset.mime.startswith("image/")):
        raise Problem("Choose an MP4 reference video for this model." if reference_video else "This generation only accepts image input.")
    if not asset.key.startswith(f"accounts/{account.pk}/") or ".." in asset.key:
        raise Problem("Source media ownership is invalid.", 503)
    if asset.size > (200 * 1024**2 if reference_video else settings.GEMINI_MAX_INPUT_BYTES):
        raise Problem("Reference video must be under 200 MB." if reference_video else "Source image is too large for this model. Choose a smaller photo.")
    return asset


def reference_images(account, identifiers, config):
    if not isinstance(identifiers, list) or len(identifiers) > config.get("max_reference_images", 0):
        raise Problem("This model accepts up to four reference photos alongside one video.")
    try:
        ids = [uuid.UUID(str(value)) for value in identifiers]
    except (ValueError, TypeError, AttributeError):
        raise Problem("Invalid reference photo.") from None
    if len(ids) != len(set(ids)):
        raise Problem("Choose different reference photos.")
    # At most four indexed, account-scoped lookups; order defines @Image1, @Image2.
    return [{"id": str(asset.pk), "key": asset.key} for asset in
            (source_asset(account, identifier, "image", {}) for identifier in ids)]


def create_quote(account, data):
    if not isinstance(data, dict) or set(data) - {"service", "prompt", "duration", "asset_id", "input_kind", "aspect_ratio", "generated_audio", "reference_image_count", "reference_image_ids", "template_id"}:
        raise Problem("Use service, prompt, optional duration, asset_id, input_kind, aspect_ratio and generated_audio only.")
    try:
        rate = enabled_rates().get(pk=data.get("service"))
    except Rate.DoesNotExist:
        raise Problem("This service is not available.", 409) from None
    require_rate_access(account, rate)
    prompt = data.get("prompt", "")
    if not isinstance(prompt, str) or not 1 <= len(prompt.strip()) <= (5000 if rate.kind == "voice" else 500):
        raise Problem("Enter a prompt within the service limit.")
    duration = data.get("duration", 5)
    rate_config = rate.config if isinstance(rate.config, dict) else {}
    supported_durations = rate_config.get("durations", [5, 10, 15])
    if rate.kind == "video" and (not isinstance(supported_durations, list) or not supported_durations):
        raise Problem("This model's duration configuration is unavailable.", 503)
    if rate.kind == "video" and (type(duration) is not int or duration not in supported_durations):
        raise Problem("Choose a supported duration: " + ", ".join(str(value) for value in supported_durations) + " seconds.")
    units = duration if rate.kind == "video" else (len(prompt) + 999) // 1000 if rate.kind == "voice" else 1
    if units > rate.max_units:
        raise Problem("Requested generation exceeds this service's limit.")
    payload = {"service": rate.pk, "kind": rate.kind, "model": rate.provider_model, "prompt": prompt.strip(), "units": units, "revision": rate.revision, "config": rate_config, "storage_layout": "media-v1"}
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
    input_kind = data.get("input_kind")
    if input_kind is not None:
        if not isinstance(input_kind, str) or input_kind not in ("text", "image", "video") or rate.kind not in ("image", "video"):
            raise Problem("Choose text, image or video input for this model.")
        modes = rate_config.get("input_modes")
        if modes and {"text": "text", "image": "image_and_text", "video": "video_and_text"}[input_kind] not in modes:
            raise Problem("This model does not support that input type.")
        if input_kind == "video" and not rate_config.get("reference_video"):
            raise Problem("This model does not support reference video input.")
        payload["input_kind"] = input_kind
    if data.get("asset_id"):
        asset = source_asset(account, data["asset_id"], rate.kind, rate_config)
        actual_kind = "video" if asset.mime == "video/mp4" else "image"
        if input_kind is not None and input_kind != actual_kind:
            raise Problem("Source media differs from the quoted input type.")
        payload["input_kind"] = actual_kind
        payload["input_key"] = asset.key
        payload["asset_id"] = str(asset.pk)
    elif rate_config.get("reference_video") and input_kind != "video":
        raise Problem("Select a reference video for this model.")
    if data.get("template_id"):
        template = published_template(data["template_id"])
        if (template.mode == "create_image") != (rate.kind == "image"):
            raise Problem("This template's creation mode changed. Choose it again.", 409)
        if template.requires_reference and payload.get("input_kind") != "image":
            raise Problem("Add your own reference photo to recreate this template.")
        payload.update(template_id=str(template.pk), requires_reference=template.requires_reference)
    refs = data.get("reference_image_ids", [])
    images = reference_images(account, refs, rate_config)
    count = data.get("reference_image_count", len(images))
    if type(count) is not int or not 0 <= count <= rate_config.get("max_reference_images", 0) or (images and count != len(images)):
        raise Problem("Reference photo count differs from this model's limit or uploaded photos.")
    payload.update(reference_image_count=count, reference_images=images)
    if rate_config.get("reference_video"):
        for tag in re.findall(r"@Image([0-9]+)\b", prompt):
            if not 1 <= int(tag) <= count:
                raise Problem(f"Your prompt refers to @Image{tag}. Add that reference photo or update the prompt.")
    cost = rate.unit_cost_paise * units
    charge = price(cost + rate.overhead_paise)
    ttl_minutes = 15 if input_kind in ("image", "video") and not data.get("asset_id") else 5
    return Quote.objects.create(account=account, rate=rate, payload=payload, payload_hash=digest(json.dumps(payload, sort_keys=True)), provider_cost_paise=cost, overhead_paise=rate.overhead_paise, markup_bps=settings.MARKUP_BPS, fee_bps=settings.STORE_FEE_BPS, charge_paise=charge, expires_at=timezone.now() + timedelta(minutes=ttl_minutes))


def published_template(identifier):
    try:
        return ShowcaseTemplate.objects.get(pk=uuid.UUID(str(identifier)), published=True)
    except (ValueError, ShowcaseTemplate.DoesNotExist):
        raise Problem("This template is no longer available. Choose another template.", 409) from None

@transaction.atomic
def submit_job(account, quote_id, key, asset_id=None, reference_image_ids=None):
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
    require_rate_access(account, quote.rate)
    if quote.payload.get("template_id"):
        template = published_template(quote.payload["template_id"])
        if template.requires_reference and quote.payload.get("input_kind") != "image":
            raise Problem("Add your own reference photo to recreate this template.", 409)
    if Job.objects.filter(quote=quote).exists():
        raise Problem("This quote already has a job.", 409)
    # Quotes price model/settings without an S3 upload. Bind the uploaded source
    # once, under this account's wallet lock, before reserving any credits.
    quoted_asset = quote.payload.get("asset_id")
    if asset_id is not None and quoted_asset and str(asset_id) != quoted_asset:
        raise Problem("Source media differs from the quoted source.", 409)
    source_id = asset_id if asset_id is not None else quoted_asset
    if source_id:
        if not quoted_asset and quote.payload.get("input_kind") not in ("image", "video"):
            raise Problem("This quote does not include reference media.", 409)
        asset = source_asset(account, source_id, quote.payload["kind"], quote.payload["config"])
        if not quoted_asset:
            quote.payload = {**quote.payload, "input_key": asset.key, "asset_id": str(asset.pk)}
            quote.payload_hash = digest(json.dumps(quote.payload, sort_keys=True))
            quote.save(update_fields=["payload", "payload_hash"])
    elif quote.payload.get("input_kind") in ("image", "video"):
        raise Problem("Upload your reference media before starting this generation.", 409)
    quoted_images = quote.payload.get("reference_images", [])
    supplied = reference_image_ids if reference_image_ids is not None else [row["id"] for row in quoted_images]
    images = reference_images(account, supplied, quote.payload["config"])
    if len(images) != quote.payload.get("reference_image_count", 0) or (quoted_images and images != quoted_images):
        raise Problem("Reference photos differ from your quote. Review your creation again.", 409)
    if images and not quoted_images:
        quote.payload = {**quote.payload, "reference_images": images}
        quote.payload_hash = digest(json.dumps(quote.payload, sort_keys=True))
        quote.save(update_fields=["payload", "payload_hash"])
    if wallet.available_paise < quote.charge_paise:
        raise Problem("Insufficient credits.", 402)
    if Job.objects.filter(account=account, state__in=ACTIVE_STATES).count() >= settings.MAX_ACTIVE_JOBS:
        raise Problem(f"You can run up to {settings.MAX_ACTIVE_JOBS} generations at once. Wait for one to finish.", 429)
    shard = int(digest(str(account.pk))[:8], 16) % settings.BUDGET_SHARDS
    bucket, _ = Budget.objects.get_or_create(day=timezone.now().date(), shard=shard)
    bucket = Budget.objects.select_for_update().get(pk=bucket.pk)
    # Hard partition: sum of all shard allowances never exceeds the global daily ceiling.
    allowance = settings.DAILY_PROVIDER_BUDGET_PAISE // settings.BUDGET_SHARDS
    if bucket.reserved_paise + bucket.spent_paise + quote.provider_cost_paise > allowance:
        raise Problem("Generation capacity is temporarily full. Try later.", 429)
    bucket.reserved_paise += quote.provider_cost_paise
    bucket.save(update_fields=["reserved_paise"])
    monthly_reserved = min(max(0, wallet.monthly_available_paise), quote.charge_paise)
    remaining = quote.charge_paise - monthly_reserved
    free_reserved = min(max(0, wallet.free_available_paise), remaining)
    topup_reserved = remaining - free_reserved
    if topup_reserved > max(0, wallet.topup_available_paise):
        raise Problem("Insufficient credits.", 402)
    job = Job.objects.create(account=account, quote=quote, idempotency_key=key, budget=bucket, next_attempt_at=timezone.now(),
                             monthly_reserved_paise=monthly_reserved, topup_reserved_paise=topup_reserved,
                             monthly_period_key=wallet.monthly_period_key if monthly_reserved else "")
    entry(wallet, "reserve", f"job:{job.pk}:reserve", held_delta=quote.charge_paise,
          monthly_held_delta=monthly_reserved, topup_held_delta=topup_reserved)
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
    if state == "succeeded" and asset is None:
        raise Problem("Save the generated media before completing this job.", 503)
    if asset is not None and (str(asset.account_id) != str(job.account_id) or not asset.ready or asset.deleted):
        raise Problem("Generated media does not belong to this account.", 503)
    bucket = Budget.objects.select_for_update().get(pk=job.budget_id)
    bucket.reserved_paise -= job.quote.provider_cost_paise
    bucket.spent_paise += actual_cost
    bucket.save(update_fields=["reserved_paise", "spent_paise"])
    charged = min(job.quote.charge_paise, price(actual_cost + job.quote.overhead_paise, job.quote.markup_bps, job.quote.fee_bps)) if state == "succeeded" else 0
    monthly_charge = min(charged, job.monthly_reserved_paise)
    free_reserved = job.quote.charge_paise - job.monthly_reserved_paise - job.topup_reserved_paise
    free_charge = min(charged - monthly_charge, free_reserved)
    topup_charge = charged - monthly_charge - free_charge
    entry(wallet, "charge" if charged else "release", f"job:{job.pk}:settle", balance_delta=-charged, held_delta=-job.quote.charge_paise,
          monthly_balance_delta=-monthly_charge, monthly_held_delta=-job.monthly_reserved_paise,
          topup_balance_delta=-topup_charge, topup_held_delta=-job.topup_reserved_paise)
    expired_release = job.monthly_reserved_paise - monthly_charge
    if expired_release and (job.monthly_period_key != wallet.monthly_period_key or
                            (wallet.monthly_expires_at and wallet.monthly_expires_at <= timezone.now())):
        entry(wallet, "monthly_expire", f"job:{job.pk}:expired-release", balance_delta=-expired_release,
              monthly_balance_delta=-expired_release)
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
