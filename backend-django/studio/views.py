import uuid
import ipaddress
from datetime import datetime, timezone as dt_timezone
from django.conf import settings
from django.core.cache import cache
from django.db import connection, transaction
from django.utils import timezone
from rest_framework.views import APIView
from rest_framework.permissions import AllowAny
from rest_framework.response import Response
from rest_framework.pagination import CursorPagination
from . import authentication, services, storage, billing
from .models import Account, Wallet, Ledger, Revocation, Job, Asset, Product, Purchase
from .errors import Problem

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
        except Exception:
            raise Problem("Google identity could not be verified.", 401) from None
        with transaction.atomic():
            account, _ = Account.objects.get_or_create(google_sub=profile["sub"], defaults={"email": profile["email"], "name": str(profile.get("name", profile["email"]))[:200]})
            if not account.is_active:
                raise Problem("Account is disabled.", 403)
            account.email = profile["email"]
            account.name = str(profile.get("name", profile["email"]))[:200]
            picture = profile.get("picture", "")
            account.picture = picture[:2048] if isinstance(picture, str) and picture.startswith("https://") else ""
            account.save(update_fields=["email", "name", "picture"])
            Wallet.objects.get_or_create(account=account)
        return ok(token=authentication.issue_token(account), user=authentication.account_public(account))

class Me(Private):
    def get(self, request):
        return ok(appId="motionmagic", user=authentication.account_public(request.user))

class Logout(Private):
    def post(self, request):
        claims = request.auth
        Revocation.objects.get_or_create(jti_hash=authentication.digest(claims["jti"]), defaults={"expires_at": datetime.fromtimestamp(claims["exp"], dt_timezone.utc)})
        cache.set("motion:revoked:" + authentication.digest(claims["jti"]), True, max(1, claims["exp"] - int(timezone.now().timestamp())))
        return ok()

class Capabilities(Public):
    def get(self, request):
        rates = list(services.enabled_rates().order_by("slug")[:200])
        return ok(local=["photo_animation", "device_voice", "video_editor", "audio_editor", "local_projects"], cloud=[{
            "id": r.pk, "title": r.title, "kind": r.kind, "model": r.provider_model, "max_units": r.max_units, "config": r.config,
            "minimum_units": min(r.config.get("durations", [5])) if r.kind == "video" else 1,
            "minimum_charge_paise": services.price(r.unit_cost_paise * (min(r.config.get("durations", [5])) if r.kind == "video" else 1) + r.overhead_paise),
        } for r in rates], billing=settings.PLAY_ENABLED and bool(rates), subscriptions=False, currency="INR", markup_bps=settings.MARKUP_BPS)

class WalletView(Private):
    def get(self, request):
        wallet, _ = Wallet.objects.get_or_create(account=request.user)
        return ok(currency="INR", balance_paise=wallet.balance_paise, held_paise=wallet.held_paise, available_paise=wallet.available_paise, billing_account_id=authentication.digest(str(request.user.pk)))

class Transactions(Private):
    def get(self, request):
        return page(request, Ledger.objects.filter(account=request.user), lambda row: {"id": str(row.pk), "kind": row.kind, "balance_delta_paise": row.balance_delta, "held_delta_paise": row.held_delta, "balance_after_paise": row.balance_after, "held_after_paise": row.held_after, "created_at": row.created_at, "reference": row.reference})

class Quotes(Private):
    def post(self, request):
        authentication.rate_limit("quotes:" + str(request.user.pk), 20)
        quote = services.create_quote(request.user, request.data)
        return ok(id=str(quote.pk), currency="INR", maximum_charge_paise=quote.charge_paise, expires_at=quote.expires_at, markup_bps=quote.markup_bps, title=quote.rate.title)

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
        products = Product.objects.filter(enabled=True, credit_paise__gt=0).order_by("credit_paise")[:30] if settings.PLAY_ENABLED and services.enabled_rates().exists() else []
        return ok(products=[{"sku": p.pk, "title": p.title, "credit_paise": p.credit_paise} for p in products])

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
