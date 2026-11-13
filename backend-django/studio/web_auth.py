"""Browser-only sessions. Google subject, wallet and media remain shared with Android."""
import secrets
import ipaddress
from datetime import timedelta
from zoneinfo import ZoneInfo

from django.conf import settings
from django.core import signing
from django.db import transaction
from django.db.models import F
from django.middleware.csrf import get_token, rotate_token
from django.utils import timezone
from django.utils.decorators import method_decorator
from django.views.decorators.csrf import csrf_protect
from rest_framework.authentication import BaseAuthentication, SessionAuthentication
from rest_framework.exceptions import AuthenticationFailed
from rest_framework.permissions import AllowAny
from rest_framework.response import Response
from rest_framework.views import APIView

from . import authentication, services
from .errors import Problem
from .models import Account, Wallet, WebSession, WebUserProfile, WebUserDay


def testing_config():
    return {"enabled": False, "welcome_credits": settings.WELCOME_CREDITS,
            "purchases_enabled": settings.PAYU_ENABLED}


def record_activity(account, created=False, login=False):
    now = timezone.now()
    WebUserProfile.objects.get_or_create(account=account, defaults={"registered_on_web": created})
    changes = {"last_seen": now}
    if login:
        changes["last_login"] = now
    WebUserProfile.objects.filter(account=account).update(**changes)
    day, _ = WebUserDay.objects.get_or_create(account=account, day=timezone.localdate(now, ZoneInfo("Asia/Kolkata")))
    field = "logins" if login else "visits"
    WebUserDay.objects.filter(pk=day.pk).update(**{field: F(field)+1})


class BrowserAuthentication(BaseAuthentication):
    def authenticate_header(self, request):
        return "WebSession"

    def authenticate(self, request):
        if not settings.WEB_ENABLED:
            return None
        token = request.COOKIES.get(settings.WEB_SESSION_COOKIE, "")
        if not token:
            return None
        if len(token) != 66 or not token.startswith("w_"):
            raise AuthenticationFailed("Web session expired. Sign in again.")
        saved = WebSession.objects.select_related("account").filter(pk=authentication.digest(token), expires_at__gt=timezone.now()).first()
        if not saved or not saved.account.is_active or saved.token_version != saved.account.token_version:
            raise AuthenticationFailed("Web session expired. Sign in again.")
        browser_link = request.method=="GET" and (request.path=="/app/session/" or request.path.startswith("/app/media/") or request.path.endswith("/checkout/"))
        if not browser_link and request.headers.get("X-Motion-Account") != str(saved.account_id):
            raise AuthenticationFailed("Google account changed. Reload your studio.")
        SessionAuthentication().enforce_csrf(request)
        return saved.account, {"web_session": saved.pk}


@method_decorator(csrf_protect, name="dispatch")
class BrowserPublic(APIView):
    authentication_classes = []
    permission_classes = [AllowAny]

    def initial(self, request, *args, **kwargs):
        super().initial(request, *args, **kwargs)
        if not settings.WEB_ENABLED:
            raise Problem("Web access is not enabled.", 404)


class BrowserConfig(BrowserPublic):
    def get(self, request):
        user = None
        try:
            result = BrowserAuthentication().authenticate(request)
            user = authentication.account_public(result[0]) if result else None
            if result:
                record_activity(result[0])
        except AuthenticationFailed:
            pass
        nonce = secrets.token_urlsafe(32)
        response = Response({"ok": True, "user": user, "csrf": get_token(request._request),
                             "google_client_id": settings.GOOGLE_CLIENT_ID if settings.AUTH_ENABLED else "", "nonce": nonce, "testing": testing_config(),
                             "welcome_credits": settings.WELCOME_CREDITS, "visitor_prompt": False})
        response.set_cookie("motion_web_nonce", signing.dumps(nonce, salt="motion-web-login"), max_age=600,
                            httponly=True, secure=not settings.TESTING, samesite="Lax", path="/app/")
        return response


class BrowserSignIn(BrowserPublic):
    def post(self, request):
        if not settings.AUTH_ENABLED:
            raise Problem("Google sign-in is not enabled.", 503)
        peer = request.META.get("REMOTE_ADDR", "unknown")
        if settings.PROXY_MODE in ("alb", "render"):
            try:
                peer = str(ipaddress.ip_address(request.headers.get("X-Forwarded-For", "").split(",")[-1].strip()))
            except ValueError:
                raise Problem("Invalid proxy routing.", 400) from None
        authentication.rate_limit("web-login:" + peer, 30)
        token = request.data.get("credential") if isinstance(request.data, dict) else None
        if not isinstance(token, str) or not 20 <= len(token) <= 10000:
            raise Problem("A Google identity token is required.")
        try:
            nonce = signing.loads(request.COOKIES.get("motion_web_nonce", ""), salt="motion-web-login", max_age=600)
            profile = authentication.verify_google(token)
            if not secrets.compare_digest(str(profile.get("nonce", "")), nonce):
                raise ValueError("nonce")
        except Exception:
            raise Problem("Google identity could not be verified. Reload and sign in again.", 401) from None
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
            record_activity(account, created=created, login=True)
            previous = request.COOKIES.get(settings.WEB_SESSION_COOKIE, "")
            if previous:
                WebSession.objects.filter(pk=authentication.digest(previous)).delete()
            session = "w_" + secrets.token_urlsafe(48)
            WebSession.objects.create(token_hash=authentication.digest(session), account=account,
                                      token_version=account.token_version, expires_at=timezone.now()+timedelta(days=settings.REFRESH_DAYS))
        rotate_token(request._request)
        response = Response({"ok": True, "user": authentication.account_public(account), "csrf": get_token(request._request), "testing": testing_config()})
        response.set_cookie(settings.WEB_SESSION_COOKIE, session, max_age=settings.REFRESH_DAYS*86400,
                            httponly=True, secure=not settings.TESTING, samesite="Lax", path="/")
        response.delete_cookie("motion_web_nonce", path="/app/", samesite="Lax")
        return response


class BrowserSignOut(BrowserPublic):
    def post(self, request):
        token = request.COOKIES.get(settings.WEB_SESSION_COOKIE, "")
        if token:
            BrowserAuthentication().authenticate(request)
            WebSession.objects.filter(pk=authentication.digest(token)).delete()
        response = Response({"ok": True})
        response.delete_cookie(settings.WEB_SESSION_COOKIE, path="/", samesite="Lax")
        return response
