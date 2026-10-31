import uuid
import ipaddress
import logging
import base64
import json
from datetime import datetime, timezone as dt_timezone
from django.conf import settings
from django.core.cache import cache
from django.db import connection, transaction
from django.utils import timezone
from rest_framework.views import APIView
from rest_framework.permissions import AllowAny
from rest_framework.response import Response
from rest_framework.pagination import CursorPagination
from google.auth.transport import requests as google_requests
from google.oauth2 import id_token as google_id_token
from . import authentication, services, storage, billing
from .models import Account, Wallet, Ledger, Revocation, RefreshSession, Job, Asset, Product, Purchase, Subscription
from .errors import Problem

logger = logging.getLogger(__name__)

def ok(**data):
    return Response({"ok": True, **data})

def body(request, allowed):
    value = request.data
    if not isinstance(value, dict) or set(value) - set(allowed):
        raise Problem("Request contains unsupported fields.")
    return value

class Page(CursorPagination):
    page_size = 30
    ordering = "-created_at"

def page(request, queryset, serialize):
    paginator = Page()
    rows = paginator.paginate_queryset(queryset, request)
    return ok(results=[serialize(row) for row in rows], next=paginator.get_next_link())

class Public(APIView):
    permission_classes = [AllowAny]
    authentication_classes = []

class Private(APIView):
    def initial(self, request, *args, **kwargs):
        super().initial(request, *args, **kwargs)
        authentication.rate_limit("account:" + str(request.user.pk), 120)

class Health(Public):
    def get(self, request):
        return ok(service="motionmagic-django")

class Ready(Public):
    def get(self, request):
        with connection.cursor() as cursor:
            cursor.execute("SELECT 1")
        cache.set("motion:readiness", True, 10)
        if cache.get("motion:readiness") is not True:
            raise Problem("Cache unavailable.", 503)
        return ok()

class AuthConfig(Public):
    def get(self, request):
        return ok(appId="motionmagic", enabled=settings.AUTH_ENABLED, googleWebClientId=settings.GOOGLE_CLIENT_ID if settings.AUTH_ENABLED else "")

class SignIn(Public):
    def post(self, request):
        if not settings.AUTH_ENABLED:
            raise Problem("Sign-in is not enabled.", 503)
        # Use the rightmost address supplied by the trusted platform edge, never a client-supplied prefix.
        peer = request.META.get("REMOTE_ADDR", "unknown")
        if settings.PROXY_MODE in ("alb", "render"):
            candidate = request.headers.get("X-Forwarded-For", "").split(",")[-1].strip()
            try:
                peer = str(ipaddress.ip_address(candidate))
            except ValueError:
                raise Problem("Invalid proxy routing.", 400) from None
        authentication.rate_limit("login:" + peer, 30)
        data = body(request, ("app", "idToken"))
        token = data.get("idToken")
        if data.get("app") != "motionmagic" or not isinstance(token, str) or not 20 <= len(token) <= 10000:
            raise Problem("A MotionMagic Google identity token is required.")
        try:
            profile = authentication.verify_google(token)
        except Exception as exc:
            # Never log an ID token or its claims. The exception type is enough to diagnose
            # certificate, network and audience failures from Render's server logs.
            logger.warning("google_identity_verification_failed error_type=%s", type(exc).__name__)
            raise Problem("Google identity could not be verified.", 401) from None
        try:
            with transaction.atomic():
                account, created = Account.objects.get_or_create(google_sub=profile["sub"], defaults={"email": profile["email"], "name": str(profile.get("name", profile["email"]))[:200]})
                if not account.is_active:
                    raise Problem("Account is disabled.", 403)
                account.email = profile["email"]
                account.name = str(profile.get("name", profile["email"]))[:200]
                picture = profile.get("picture", "")
                account.picture = picture[:2048] if isinstance(picture, str) and picture.startswith("https://") else ""
                account.save(update_fields=["email", "name", "picture"])
                Wallet.objects.get_or_create(account=account)
                services.grant_welcome_credits(account)
                refresh_token = authentication.issue_refresh(account)
        except Problem:
            raise
        except Exception:
            # This is intentionally after token verification and excludes the token/profile.
            # Render receives the database traceback needed to identify missing migrations.
            logger.exception("google_sign_in_account_provisioning_failed")
            raise
        return ok(token=authentication.issue_token(account), refreshToken=refresh_token, user=authentication.account_public(account))

class Refresh(Public):
    def post(self, request):
        data = body(request, ("refreshToken",))
        presented = data.get("refreshToken")
        if not isinstance(presented, str) or not 40 <= len(presented) <= 256:
            raise Problem("Invalid saved session.", 401)
        authentication.rate_limit("refresh:" + authentication.digest(presented), 5)
        account, refresh_token = authentication.rotate_refresh(presented)
        return ok(token=authentication.issue_token(account), refreshToken=refresh_token, user=authentication.account_public(account))

class RevokeRefresh(Public):
    def post(self, request):
        data = body(request, ("refreshToken",))
        token = data.get("refreshToken")
        if isinstance(token, str) and 40 <= len(token) <= 256:
            RefreshSession.objects.filter(token_hash=authentication.digest(token)).update(revoked_at=timezone.now())
        return ok()

class Me(Private):
    def get(self, request):
        return ok(appId="motionmagic", user=authentication.account_public(request.user))

class Logout(Private):
    def post(self, request):
        claims = request.auth
        Revocation.objects.get_or_create(jti_hash=authentication.digest(claims["jti"]), defaults={"expires_at": datetime.fromtimestamp(claims["exp"], dt_timezone.utc)})
        cache.set("motion:revoked:" + authentication.digest(claims["jti"]), True, max(1, claims["exp"] - int(timezone.now().timestamp())))
        data = body(request, ("refreshToken",))
        refresh_token = data.get("refreshToken")
        if isinstance(refresh_token, str) and 40 <= len(refresh_token) <= 256:
            RefreshSession.objects.filter(token_hash=authentication.digest(refresh_token), account=request.user).update(revoked_at=timezone.now())
        return ok()

class Capabilities(Public):
    def get(self, request):
        rates = list(services.enabled_rates().order_by("slug")[:200])
        return ok(local=["photo_animation", "device_voice", "video_editor", "audio_editor", "local_projects"], cloud=[{
            "id": r.pk, "title": r.title, "kind": r.kind, "model": r.provider_model, "max_units": r.max_units, "config": r.config,
            "minimum_units": min(r.config.get("durations", [5])) if r.kind == "video" else 1,
            "minimum_charge_paise": services.price(r.unit_cost_paise * (min(r.config.get("durations", [5])) if r.kind == "video" else 1) + r.overhead_paise),
        } for r in rates], billing=settings.PLAY_ENABLED and bool(rates), subscriptions=settings.PLAY_ENABLED and settings.SUBSCRIPTIONS_ENABLED, currency="CREDITS", credit_value_usd_cents=0.5, markup_bps=settings.MARKUP_BPS)

class WalletView(Private):
    def get(self, request):
        with transaction.atomic():
            services.grant_welcome_credits(request.user)
            wallet, _ = Wallet.objects.get_or_create(account=request.user)
        return ok(currency="CREDITS", balance_paise=wallet.balance_paise, held_paise=wallet.held_paise, available_paise=wallet.available_paise,
                  balance_credits=wallet.balance_paise, held_credits=wallet.held_paise, available_credits=wallet.available_paise,
                  billing_account_id=authentication.digest(str(request.user.pk)))

class Transactions(Private):
    def get(self, request):
        return page(request, Ledger.objects.filter(account=request.user), lambda row: {"id": str(row.pk), "kind": row.kind, "balance_delta_paise": row.balance_delta, "held_delta_paise": row.held_delta, "balance_after_paise": row.balance_after, "held_after_paise": row.held_after, "created_at": row.created_at, "reference": row.reference})

class Quotes(Private):
    def post(self, request):
        authentication.rate_limit("quotes:" + str(request.user.pk), 20)
        quote = services.create_quote(request.user, request.data)
        return ok(id=str(quote.pk), currency="CREDITS", maximum_charge_paise=quote.charge_paise, maximum_credits=quote.charge_paise,
                  expires_at=quote.expires_at, markup_bps=quote.markup_bps, title=quote.rate.title)

def job_public(job):
    payload = job.quote.payload
    return {"id": str(job.pk), "state": job.state, "maximum_charge_paise": job.quote.charge_paise, "charged_paise": job.charged_paise, "output_asset_id": str(job.output_id) if job.output_id else None, "error_code": job.error_code, "created_at": job.created_at, "poll_after_seconds": 30 if job.state not in services.TERMINAL else None,
            "title": payload.get("prompt", "AI creation")[:55], "kind": payload.get("kind"), "aspect_ratio": payload.get("aspect_ratio"), "duration": payload.get("units", 0) if payload.get("kind") == "video" else 0}

class Jobs(Private):
    def get(self, request):
        return page(request, Job.objects.filter(account=request.user).select_related("quote"), job_public)
    def post(self, request):
        data = body(request, ("quote_id",))
        job, created = services.submit_job(request.user, data.get("quote_id"), request.headers.get("Idempotency-Key"))
        return Response({"ok": True, **job_public(job)}, status=202 if created else 200)

class JobDetail(Private):
    def get(self, request, identifier):
        try:
            job = Job.objects.select_related("quote").get(pk=identifier, account=request.user)
        except Job.DoesNotExist:
            raise Problem("Job not found.", 404) from None
        return ok(**job_public(job))
    def delete(self, request, identifier):
        return ok(**job_public(services.cancel_job(request.user, identifier)))

def asset_public(asset):
    return {"id": str(asset.pk), "mime": asset.mime, "size": asset.size, "ready": asset.ready, "created_at": asset.created_at}

class Assets(Private):
    def get(self, request):
        return page(request, Asset.objects.filter(account=request.user, deleted=False, ready=True), asset_public)
    def post(self, request):
        authentication.rate_limit("uploads:" + str(request.user.pk), 10)
        data = body(request, ("mime", "size"))
        asset, upload = storage.begin_upload(request.user, data.get("mime"), data.get("size"))
        return ok(asset=asset_public(asset), upload=upload, expires_in=300)

class AssetDetail(Private):
    def find(self, request, identifier):
        try:
            return Asset.objects.get(pk=identifier, account=request.user, deleted=False)
        except Asset.DoesNotExist:
            raise Problem("Media not found.", 404) from None
    def post(self, request, identifier):
        return ok(asset=asset_public(storage.confirm_upload(self.find(request, identifier))))
    def get(self, request, identifier):
        asset = self.find(request, identifier)
        if not asset.ready:
            raise Problem("Media upload is incomplete.", 409)
        return ok(url=storage.download(asset), expires_in=60, asset=asset_public(asset))
    def delete(self, request, identifier):
        asset = self.find(request, identifier)
        if Job.objects.filter(account=request.user, quote__payload__asset_id=str(identifier)).exclude(state__in=services.TERMINAL).exists():
            raise Problem("Media is used by an active job.", 409)
        # Delete S3 first; retry is safe, and a failed delete never reports success.
        storage.client().delete_object(Bucket=settings.S3_BUCKET, Key=asset.key)
        asset.deleted = True
        asset.save(update_fields=["deleted"])
        return ok()

class Products(Private):
    def get(self, request):
        products = Product.objects.filter(enabled=True, credit_paise__gt=0).order_by("credit_paise")[:30] if settings.PLAY_ENABLED else []
        return ok(currency="CREDITS", products=[{"sku": p.pk, "title": p.title, "credit_paise": p.credit_paise, "credits": p.credit_paise} for p in products])

def purchase_public(purchase):
    return {"id": str(purchase.pk), "sku": purchase.product_id, "credit_paise": purchase.credit_paise, "state": purchase.state, "consumed": purchase.consumed, "created_at": purchase.created_at}

class VerifyPurchase(Private):
    def post(self, request):
        authentication.rate_limit("purchase:" + str(request.user.pk), 10)
        data = body(request, ("sku", "purchase_token"))
        return ok(purchase=purchase_public(billing.verify_purchase(request.user, data.get("sku"), data.get("purchase_token"))))

class Purchases(Private):
    def get(self, request):
        return page(request, Purchase.objects.filter(account=request.user), purchase_public)

def subscription_public(subscription):
    return {"product_id": subscription.product_id, "plan": subscription.plan, "state": subscription.state,
            "auto_renewing": subscription.auto_renewing, "expires_at": subscription.expires_at,
            "credits_per_period": billing.SUBSCRIPTIONS[subscription.product_id][1]}

class Entitlement(Private):
    def get(self, request):
        now = timezone.now()
        subscriptions = Subscription.objects.filter(account=request.user).order_by("-updated_at")
        active = subscriptions.filter(state__in=("active", "grace", "cancelled"), expires_at__gt=now).first()
        return ok(enabled=settings.PLAY_ENABLED and settings.SUBSCRIPTIONS_ENABLED,
                  plan=active.plan if active else "free", subscription=subscription_public(active) if active else None,
                  subscriptions=[subscription_public(row) for row in subscriptions[:10]])

class VerifySubscription(Private):
    def post(self, request):
        authentication.rate_limit("subscription:" + str(request.user.pk), 10)
        data = body(request, ("product_id", "purchase_token"))
        return ok(subscription=subscription_public(billing.verify_subscription(request.user, data.get("product_id"), data.get("purchase_token"))))

class PlayNotifications(Public):
    """Google Cloud Pub/Sub push receiver for post-purchase subscription events."""
    def post(self, request):
        if not settings.PLAY_PUBSUB_AUDIENCE:
            raise Problem("Play notifications are not configured.", 404)
        bearer = request.headers.get("Authorization", "")
        if not bearer.startswith("Bearer "):
            raise Problem("Missing Google Pub/Sub identity.", 401)
        try:
            claims = google_id_token.verify_oauth2_token(bearer[7:], google_requests.Request(), settings.PLAY_PUBSUB_AUDIENCE)
            if not settings.PLAY_PUBSUB_SERVICE_ACCOUNT or claims.get("email") != settings.PLAY_PUBSUB_SERVICE_ACCOUNT or not claims.get("email_verified"):
                raise ValueError()
            message = request.data.get("message")
            if not isinstance(message, dict) or not isinstance(message.get("messageId"), str) or not isinstance(message.get("data"), str):
                raise ValueError()
            message_id = message["messageId"]
            if not cache.add("motion:play-rtdn:" + message_id, True, 7 * 86400):
                return Response(status=204)
            decoded = base64.b64decode(message["data"], validate=True)
            event = json.loads(decoded.decode("utf-8"))
            notice = event.get("subscriptionNotification") or {}
            token = notice.get("purchaseToken")
            if not isinstance(token, str) or not 10 <= len(token) <= 4096:
                return Response(status=204)  # Test and non-subscription messages.
            subscription = Subscription.objects.filter(token_hash=authentication.digest(token)).first()
            if subscription:
                billing.reconcile_subscription(subscription)
        except Problem:
            raise
        except Exception as exc:
            logger.warning("play_rtdn_rejected error_type=%s", type(exc).__name__)
            raise Problem("Invalid Google Play notification.", 401) from None
        return Response(status=204)
