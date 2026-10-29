import hashlib
import time
import uuid
import threading
import jwt
import requests
from cachecontrol import CacheControl
from django.conf import settings
from django.core.cache import cache
from google.auth.transport.requests import Request
from google.oauth2 import id_token
from rest_framework.authentication import BaseAuthentication
from rest_framework.exceptions import AuthenticationFailed
from .models import Account, Revocation
from .errors import Problem

class CertificateRequest(Request):
    def __call__(self, *args, **kwargs):
        kwargs["timeout"] = 5
        return super().__call__(*args, **kwargs)
_google_local = threading.local()

def google_request():
    request = getattr(_google_local, "request", None)
    if request is None:
        # Cache Google certificates per worker thread without sharing requests.Session across threads.
        request = CertificateRequest(session=CacheControl(requests.Session()))
        _google_local.request = request
    return request

def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()

def account_public(account):
    return {"id": str(account.pk), "email": account.email, "name": account.name, "picture": account.picture}

def issue_token(account):
    now = int(time.time())
    return jwt.encode({"iss": "motionmagic-ai", "aud": "motionmagic", "appId": "motionmagic", "sub": str(account.pk), "iat": now, "exp": now + settings.SESSION_SECONDS, "jti": str(uuid.uuid4()), "ver": account.token_version}, settings.JWT_SECRET, algorithm="HS256")

def verify_google(token):
    # Cache Google's certificate responses with their HTTP cache policy; still verify each JWT.
    profile = id_token.verify_oauth2_token(token, google_request(), settings.GOOGLE_CLIENT_ID)
    if profile.get("email_verified") is not True or not isinstance(profile.get("sub"), str) or not profile["sub"] or len(profile["sub"]) > 255:
        raise ValueError("Unverified account")
    if not isinstance(profile.get("email"), str) or not 1 <= len(profile["email"]) <= 320:
        raise ValueError("Invalid email")
    return profile

class MotionAuthentication(BaseAuthentication):
    def authenticate_header(self, request):
        return "Bearer"
    def authenticate(self, request):
        header = request.headers.get("Authorization", "")
        if not header:
            return None
        try:
            if not header.startswith("Bearer ") or len(header) > 4103:
                raise ValueError()
            claims = jwt.decode(header[7:], settings.JWT_SECRET, algorithms=["HS256"], issuer="motionmagic-ai", audience="motionmagic", options={"require": ["exp", "iat", "jti", "sub", "appId", "ver"]})
            if claims["appId"] != "motionmagic" or not isinstance(claims["jti"], str) or not 1 <= len(claims["jti"]) <= 128:
                raise ValueError()
            revocation_key = "motion:revoked:" + digest(claims["jti"])
            revoked = cache.get(revocation_key)
            if revoked is None:
                revoked = Revocation.objects.filter(pk=digest(claims["jti"])).exists()
                cache.add(revocation_key, revoked, 30)
            if revoked:
                raise ValueError()
            account_key = "motion:account:" + digest(claims["sub"])
            profile = cache.get(account_key)
            if profile is None:
                account = Account.objects.get(pk=claims["sub"], is_active=True, token_version=claims["ver"])
                profile = {field: getattr(account, field) for field in ("id", "google_sub", "email", "name", "picture", "is_active", "token_version")}
                cache.set(account_key, profile, 30)
            account = Account(**profile)
            if not account.is_active or account.token_version != claims["ver"]:
                raise ValueError()
        except (jwt.PyJWTError, ValueError, KeyError, Account.DoesNotExist):
            raise AuthenticationFailed("Invalid or expired session. Sign in again.") from None
        return account, claims

def rate_limit(key, limit, seconds=60):
    key = "motion:limit:" + digest(key)
    try:
        if cache.add(key, 1, timeout=seconds):
            return
        if cache.incr(key) > limit:
            raise Problem("Too many requests. Retry shortly.", 429)
    except Problem:
        raise
    except Exception:
        # No per-process fallback that would bypass limits across replicas.
        raise Problem("Rate limiter temporarily unavailable.", 503) from None
