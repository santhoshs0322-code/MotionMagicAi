"""Temporary browser-only access gate and anonymous daily document counters."""
import base64
import binascii
import ipaddress
from urllib.parse import urlsplit
import logging
import secrets
from zoneinfo import ZoneInfo

from django.conf import settings
from django.core import signing
from django.db import DatabaseError
from django.db.models import F
from django.http import HttpResponse
from django.utils import timezone
from django.utils.crypto import constant_time_compare, salted_hmac

from .authentication import rate_limit
from .errors import Problem
from .models import WebVisitorDay

logger = logging.getLogger(__name__)
COOKIE = "motion_web_visitor"
COOKIE_AGE = 30 * 86400


def gate(request):
    browser_api = request.path.startswith(("/api/", "/auth/")) and settings.WEB_SESSION_COOKIE in request.COOKIES and not request.headers.get("Authorization", "").startswith("Bearer ")
    protected = request.path == "/" or request.path.startswith("/app/") or browser_api
    # Third-party payment callbacks must remain reachable without tester credentials.
    if not settings.WEB_TEST_GATE_ENABLED or not protected or request.path.startswith("/app/payments/payu/"):
        return None
    valid = False
    auth = request.headers.get("Authorization", "")
    try:
        scheme, encoded = auth.split(" ", 1)
        if scheme.lower() == "basic" and len(encoded) <= 2048:
            username, password = base64.b64decode(encoded, validate=True).decode("utf-8").split(":", 1)
            valid = constant_time_compare(username, settings.WEB_TEST_USERNAME) & constant_time_compare(password, settings.WEB_TEST_PASSWORD)
    except (ValueError, UnicodeError, binascii.Error):
        pass
    if valid:
        # The gate is independent of Google/session authentication downstream.
        request.META.pop("HTTP_AUTHORIZATION", None)
        request.__dict__.pop("headers", None)
        return None
    peer = request.META.get("REMOTE_ADDR", "unknown")
    if settings.PROXY_MODE in ("alb", "render"):
        try:
            peer = str(ipaddress.ip_address(request.headers.get("X-Forwarded-For", "").split(",")[-1].strip()))
        except ValueError:
            return HttpResponse("Invalid proxy routing.", status=400, content_type="text/plain")
    try:
        rate_limit("web-test-gate:" + peer, 30, seconds=300)
    except Problem as error:
        response = HttpResponse("Access temporarily unavailable. Please retry later.", status=error.status_code, content_type="text/plain")
        response["Retry-After"] = "300"
        return response
    response = HttpResponse("MotionMagic AI private testing. Enter your tester ID and password.", status=401, content_type="text/plain")
    response["WWW-Authenticate"] = 'Basic realm="MotionMagic AI testing", charset="UTF-8"'
    response["X-Robots-Tag"] = "noindex, nofollow"
    return response


def track(request, response):
    if not settings.WEB_ENABLED or not settings.WEB_VISITOR_TRACKING_ENABLED or request.method != "GET" or request.path not in ("/", "/app/", "/app/pricing/") or response.status_code not in (200, 401):
        return
    new_browser = False
    try:
        visitor = signing.loads(request.COOKIES.get(COOKIE, ""), salt=COOKIE, max_age=COOKIE_AGE)
        if not isinstance(visitor, str) or len(visitor) != 32:
            raise signing.BadSignature()
    except signing.BadSignature:
        visitor = secrets.token_hex(16)
        new_browser = True
    response.set_cookie(COOKIE, signing.dumps(visitor, salt=COOKIE), max_age=COOKIE_AGE,
        secure=not settings.TESTING, httponly=True, samesite="Lax", path="/")
    if not settings.WEB_VISITOR_TRACKING_ENABLED:
        return
    day = timezone.localdate(timezone=ZoneInfo("Asia/Kolkata"))
    identity = salted_hmac("web-visitor-daily", day.isoformat() + ":" + visitor, algorithm="sha256").hexdigest()
    try:
        agent = request.headers.get("User-Agent", "").lower()
        device = "bot" if any(x in agent for x in ("bot", "spider", "crawler")) else "mobile" if any(x in agent for x in ("mobile", "android", "iphone", "ipad")) else "desktop"
        source = request.GET.get("utm_source", "").lower()
        if source not in ("google", "youtube", "instagram", "facebook", "whatsapp", "telegram", "email"):
            try:
                ref = (urlsplit(request.headers.get("Referer", "")).hostname or "").lower()
            except ValueError:
                ref = ""
            source = "internal" if ref==request.get_host().split(":")[0] else "referral" if ref else "direct"
            for domain, label in (("google.com", "google"), ("youtube.com", "youtube"), ("instagram.com", "instagram"), ("facebook.com", "facebook"), ("t.co", "social")):
                if ref==domain or ref.endswith("."+domain):
                    source=label
                    break
        row, _ = WebVisitorDay.objects.get_or_create(day=day, visitor_hash=identity,
            defaults={"new_browser": new_browser, "source": source, "device": device, "landing": request.path})
        WebVisitorDay.objects.filter(pk=row.pk).update(views=F("views")+1, admitted_views=F("admitted_views")+int(response.status_code==200))
        # No raw IP, user agent or Google identity is retained.
    except DatabaseError:
        logger.warning("Web visitor counter unavailable")
        return
