"""Same-origin web UI and browser-only checkout. Android uses its existing endpoints."""
import hashlib
import logging
import mimetypes
import uuid
from functools import lru_cache
from pathlib import Path

from django.conf import settings
from django.http import FileResponse, Http404, HttpResponse, HttpResponseNotModified
from django.middleware.csrf import get_token
from django.shortcuts import redirect, render
from rest_framework.permissions import IsAuthenticated
from rest_framework.response import Response

from . import authentication, storage, web_payments
from .errors import Problem
from .models import Asset, WebCreditPack, WebPayment
from .web_auth import BrowserAuthentication, BrowserPublic

logger = logging.getLogger(__name__)
STATIC = Path(__file__).parent / "static" / "motion_web"
FILES = frozenset(("app.js", "app.css", "logo.webp", "hero.webp", "long-story.webp", "font-regular.ttf", "font-bold.ttf"))


@lru_cache(maxsize=10)
def version(name):
    return hashlib.sha256((STATIC/name).read_bytes()).hexdigest()[:16]


def index(request):
    if not settings.WEB_ENABLED:
        raise Http404
    if settings.WEB_PUBLIC_URL and not settings.TESTING:
        from urllib.parse import urlsplit
        if request.get_host() != urlsplit(settings.WEB_PUBLIC_URL).netloc:
            return redirect(settings.WEB_PUBLIC_URL+"/app/")
    response = render(request, "web/app.html", {"files": {name.replace(".", "_").replace("-", "_"): f"/app/assets/{version(name)}/{name}" for name in FILES}, "csrf": get_token(request)})
    response["Cache-Control"] = "no-store, private"
    return response


def asset(request, fingerprint, name):
    if name not in FILES or fingerprint != version(name):
        raise Http404
    etag = '"'+fingerprint+'"'
    response = HttpResponseNotModified() if request.headers.get("If-None-Match")==etag else FileResponse((STATIC/name).open("rb"), content_type=mimetypes.guess_type(name)[0] or "application/octet-stream")
    response["ETag"] = etag
    response["Cache-Control"] = "public, max-age=31536000, immutable"
    return response


def font_asset(request, name):
    if name not in ("font-regular.ttf", "font-bold.ttf"):
        raise Http404
    return redirect(f"/app/assets/{version(name)}/{name}")


class BrowserPrivate(BrowserPublic):
    authentication_classes = [BrowserAuthentication]
    permission_classes = [IsAuthenticated]

    def initial(self, request, *args, **kwargs):
        super().initial(request, *args, **kwargs)
        authentication.rate_limit("web:" + str(request.user.pk), 120)


class Packs(BrowserPrivate):
    def get(self, request):
        return Response({"ok": True, "enabled": settings.PAYU_ENABLED, "test": settings.PAYU_MODE=="test",
            "packs": [dict(id=p.pk, title=p.title, credits=p.credits, amount_paise=p.amount_paise, currency="INR")
                      for p in WebCreditPack.objects.filter(enabled=True, amount_paise__gt=0).order_by("credits")]})


class Orders(BrowserPrivate):
    def get(self, request):
        rows = WebPayment.objects.filter(account=request.user).order_by("-created_at", "-id")[:30]
        return Response({"ok": True, "orders": [web_payments.public(row) for row in rows]})

    def post(self, request):
        authentication.rate_limit("web-orders:" + str(request.user.pk), 10)
        return Response({"ok": True, "order": web_payments.public(web_payments.create_order(request.user, request.data))}, status=201)


class OrderStatus(BrowserPrivate):
    def post(self, request, identifier):
        row = WebPayment.objects.filter(pk=identifier, account=request.user).first()
        if not row:
            raise Problem("Order not found.", 404)
        return Response({"ok": True, "order": web_payments.public(web_payments.reconcile(row))})


class Checkout(BrowserPrivate):
    def get(self, request, identifier):
        row = WebPayment.objects.filter(pk=identifier, account=request.user).first()
        if not row:
            raise Problem("Order not found.", 404)
        return render(request._request, "web/checkout.html", dict(fields=web_payments.checkout_fields(row),
            order=row, amount=web_payments.money(row.amount_paise), test=row.mode=="test",
            target="https://secure.payu.in/_payment" if row.mode=="live" else "https://test.payu.in/_payment"))


from rest_framework.views import APIView
from rest_framework.permissions import AllowAny
from rest_framework.parsers import FormParser, JSONParser


class GatewayReturn(APIView):
    authentication_classes = []
    permission_classes = [AllowAny]
    parser_classes = [FormParser, JSONParser]

    def post(self, request):
        web_payments.require_config()
        fields = request.data.dict() if hasattr(request.data, "dict") else request.data
        if not isinstance(fields, dict) or any(not isinstance(v, str) for v in fields.values()) or not web_payments.valid_callback(fields):
            raise Problem("PayU response could not be verified.", 400)
        try:
            identifier = uuid.UUID(fields.get("txnid", ""))
        except ValueError:
            raise Problem("Invalid payment reference.", 400) from None
        row = WebPayment.objects.filter(pk=identifier).first()
        if not row:
            raise Problem("Order not found.", 404)
        authentication.rate_limit("payu-callback:"+str(row.pk), 30)
        row = web_payments.reconcile(row)
        if row.state=="pending":
            raise Problem("Payment verification is pending. PayU may retry this notification.", 503)
        if request.path.endswith("/webhook/"):
            return HttpResponse("OK", content_type="text/plain")
        response = redirect("/app/?payment="+str(row.pk))
        response.status_code = 303
        return response


class RefundNotice(GatewayReturn):
    def post(self, request):
        # Refund callbacks are hints, not proof. Fetch all refunds from PayU.
        web_payments.require_config()
        data = request.data
        if data.get("key") != settings.PAYU_KEY:
            raise Problem("Invalid refund notification.", 400)
        try:
            identifier = uuid.UUID(data.get("merchantTxnId", ""))
        except (ValueError, TypeError, AttributeError):
            raise Problem("Invalid payment reference.", 400) from None
        row = WebPayment.objects.filter(pk=identifier).first()
        if not row:
            raise Problem("Order not found.", 404)
        authentication.rate_limit("payu-refund:"+str(row.pk), 30)
        # Use a short lease to coalesce retries, without waiting 12 hours for a refund.
        from django.utils import timezone
        from datetime import timedelta
        WebPayment.objects.filter(pk=row.pk, check_after__gt=timezone.now()+timedelta(minutes=1)).update(check_after=timezone.now())
        web_payments.reconcile(row)
        return HttpResponse("OK", content_type="text/plain")


class MediaOpen(BrowserPrivate):
    def get(self, request, identifier):
        asset = Asset.objects.filter(pk=identifier, account=request.user, deleted=False, ready=True).first()
        if not asset:
            raise Problem("Media not found.", 404)
        storage.require_owner_key(asset)
        extension = {"video/mp4": "mp4", "image/png": "png", "image/jpeg": "jpg", "image/webp": "webp", "audio/wav": "wav"}.get(asset.mime, "media")
        url = storage.client().generate_presigned_url("get_object", Params={"Bucket": settings.S3_BUCKET, "Key": asset.key,
            "ResponseContentDisposition": f'attachment; filename="MotionMagic-{asset.pk}.{extension}"'}, ExpiresIn=60)
        return redirect(url)
