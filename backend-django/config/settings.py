import os
import re
from pathlib import Path
import dj_database_url
from django.core.exceptions import ImproperlyConfigured

BASE_DIR = Path(__file__).resolve().parent.parent

def env(name, default=""):
    return os.environ.get("MOTION_AI_" + name, default)

def flag(name, default=False):
    value = env(name, str(default).lower()).lower()
    if value not in ("true", "false"):
        raise ImproperlyConfigured(f"MOTION_AI_{name} must be true or false")
    return value == "true"

DEBUG = flag("DEBUG")
TESTING = flag("TESTING")
SECRET_KEY = env("DJANGO_SECRET", "local-tests-only-not-a-production-secret" if TESTING else "")
JWT_SECRET = env("JWT_SECRET", "local-jwt-tests-only-separate-secret-12345" if TESTING else "")
if len(SECRET_KEY) < 32 or len(JWT_SECRET) < 32 or SECRET_KEY == JWT_SECRET:
    raise ImproperlyConfigured("Set separate MOTION_AI_DJANGO_SECRET and MOTION_AI_JWT_SECRET (32+ random characters)")
BEHIND_ALB = flag("BEHIND_ALB")
PROXY_MODE = env("PROXY_MODE", "alb" if BEHIND_ALB else "none").lower()
if PROXY_MODE not in ("none", "alb", "render"):
    raise ImproperlyConfigured("MOTION_AI_PROXY_MODE must be none, alb or render")
ALLOWED_HOSTS = [x.strip() for x in env("ALLOWED_HOSTS", "localhost,127.0.0.1,testserver" if TESTING else "").split(",") if x.strip()]
render_hostname = ""
if PROXY_MODE == "render":
    render_hostname = os.environ.get("RENDER_EXTERNAL_HOSTNAME", "").strip().lower()
    if not re.fullmatch(r"[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.onrender\.com", render_hostname):
        raise ImproperlyConfigured("Render must supply an exact RENDER_EXTERNAL_HOSTNAME")
    if render_hostname not in ALLOWED_HOSTS:
        ALLOWED_HOSTS.append(render_hostname)
if not ALLOWED_HOSTS or "*" in ALLOWED_HOSTS:
    raise ImproperlyConfigured("Set explicit MOTION_AI_ALLOWED_HOSTS")
INSTALLED_APPS = ["django.contrib.auth", "django.contrib.contenttypes", "rest_framework", "studio"]
MIDDLEWARE = ["django.middleware.security.SecurityMiddleware", "django.middleware.common.CommonMiddleware", "django.middleware.csrf.CsrfViewMiddleware", "django.middleware.clickjacking.XFrameOptionsMiddleware", "studio.middleware.ApiHeaders"]
ROOT_URLCONF = "config.urls"
TEMPLATES = []
WSGI_APPLICATION = "config.wsgi.application"
DATABASES = {"default": dj_database_url.parse(env("DATABASE_URL", "sqlite:///" + str(BASE_DIR / "test.sqlite3") if TESTING else ""), conn_max_age=0, conn_health_checks=True)}
if not TESTING and DATABASES["default"]["ENGINE"] != "django.db.backends.postgresql":
    raise ImproperlyConfigured("Production requires PostgreSQL; SQLite is only for tests")
if DATABASES["default"]["ENGINE"] == "django.db.backends.postgresql":
    DATABASES["default"]["DISABLE_SERVER_SIDE_CURSORS"] = True
    DATABASES["default"].setdefault("OPTIONS", {}).update({"connect_timeout": 5, "options": "-c statement_timeout=10000 -c lock_timeout=3000"})
REDIS_URL = env("REDIS_URL")
if not TESTING and not REDIS_URL:
    raise ImproperlyConfigured("Set shared MOTION_AI_REDIS_URL for multi-instance rate limiting")
CACHES = {"default": {"BACKEND": "django_redis.cache.RedisCache", "LOCATION": REDIS_URL, "OPTIONS": {"CLIENT_CLASS": "django_redis.client.DefaultClient", "SOCKET_CONNECT_TIMEOUT": 2, "SOCKET_TIMEOUT": 2}}} if REDIS_URL else {"default": {"BACKEND": "django.core.cache.backends.locmem.LocMemCache"}}
REST_FRAMEWORK = {"DEFAULT_AUTHENTICATION_CLASSES": ["studio.authentication.MotionAuthentication"], "DEFAULT_PERMISSION_CLASSES": ["rest_framework.permissions.IsAuthenticated"], "DEFAULT_RENDERER_CLASSES": ["rest_framework.renderers.JSONRenderer"], "DEFAULT_PARSER_CLASSES": ["rest_framework.parsers.JSONParser"], "EXCEPTION_HANDLER": "studio.errors.exception_handler", "UNAUTHENTICATED_USER": None}
AUTH_PASSWORD_VALIDATORS = [{"NAME": "django.contrib.auth.password_validation.MinimumLengthValidator"}, {"NAME": "django.contrib.auth.password_validation.CommonPasswordValidator"}]
USE_TZ = True
TIME_ZONE = "UTC"
DEFAULT_AUTO_FIELD = "django.db.models.BigAutoField"
APPEND_SLASH = False
DATA_UPLOAD_MAX_MEMORY_SIZE = 32 * 1024
SECURE_SSL_REDIRECT = flag("SSL_REDIRECT", not TESTING)
SECURE_HSTS_SECONDS = 31536000 if not TESTING else 0
SECURE_HSTS_INCLUDE_SUBDOMAINS = True
SECURE_HSTS_PRELOAD = True
SECURE_CONTENT_TYPE_NOSNIFF = True
CSRF_COOKIE_SECURE = not TESTING
SESSION_COOKIE_SECURE = not TESTING
# Enable only behind the selected trusted platform proxy. AWS ingress must be restricted to its ALB.
if PROXY_MODE in ("alb", "render"):
    SECURE_PROXY_SSL_HEADER = ("HTTP_X_FORWARDED_PROTO", "https")
CSRF_TRUSTED_ORIGINS = [x.strip() for x in env("CSRF_TRUSTED_ORIGINS").split(",") if x.strip()]
if render_hostname and "https://" + render_hostname not in CSRF_TRUSTED_ORIGINS:
    CSRF_TRUSTED_ORIGINS.append("https://" + render_hostname)
GOOGLE_CLIENT_ID = env("GOOGLE_CLIENT_ID")
AUTH_ENABLED = flag("AUTH_ENABLED")
SESSION_SECONDS = int(env("SESSION_SECONDS", "86400"))
AWS_REGION = env("AWS_REGION", "ap-south-1")
S3_BUCKET = env("S3_BUCKET")
AWS_ACCESS_KEY_ID = env("AWS_ACCESS_KEY_ID")
AWS_SECRET_ACCESS_KEY = env("AWS_SECRET_ACCESS_KEY")
AWS_SESSION_TOKEN = env("AWS_SESSION_TOKEN")
MAX_UPLOAD_BYTES = int(env("MAX_UPLOAD_BYTES", str(2 * 1024**3)))
GATEWAY_URL = env("GATEWAY_URL").rstrip("/")
GATEWAY_TOKEN = env("GATEWAY_TOKEN")
GEMINI_API_KEY = env("GEMINI_API_KEY")
FAL_KEY = env("FAL_KEY")
FAL_OVERHEAD_PAISE = int(env("FAL_OVERHEAD_PAISE", "0"))
from studio.fal_catalog import variants as fal_variants
FAL_RATE_COSTS = {slug: int(env(cost_env, "0")) for slug, _, _, cost_env in fal_variants()}
GEMINI_BASE_URL = env("GEMINI_BASE_URL", "https://generativelanguage.googleapis.com/v1").rstrip("/")
GEMINI_INTERACTIONS_URL = env("GEMINI_INTERACTIONS_URL", "https://generativelanguage.googleapis.com/v1beta").rstrip("/")
GEMINI_MAX_INPUT_BYTES = int(env("GEMINI_MAX_INPUT_BYTES", str(10 * 1024**2)))
GEMINI_MAX_OUTPUT_BYTES = int(env("GEMINI_MAX_OUTPUT_BYTES", str(32 * 1024**2)))
GEMINI_MAX_VIDEO_BYTES = int(env("GEMINI_MAX_VIDEO_BYTES", str(512 * 1024**2)))
GEMINI_OVERHEAD_PAISE = int(env("GEMINI_OVERHEAD_PAISE", "0"))
GEMINI_RATE_COSTS = {
    ("gemini-nano-banana-2.1", size): int(env(f"GEMINI_NANO_21_{size}_COST_PAISE", "0"))
    for size in ("1K", "2K", "4K")
} | {
    ("gemini-3.1-flash-image", size): int(env(f"GEMINI_NANO_2_{size}_COST_PAISE", "0"))
    for size in ("1K", "2K", "4K")
} | {
    ("gemini-3.1-flash-lite-image", "1K"): int(env("GEMINI_LITE_1K_COST_PAISE", "0")),
} | {
    ("gemini-3-pro-image", size): int(env(f"GEMINI_PRO_{size}_COST_PAISE", "0"))
    for size in ("1K", "2K", "4K")
}
GEMINI_VIDEO_RATE_COSTS = {
    resolution: int(env(f"GEMINI_OMNI_{resolution.upper()}_COST_PAISE_PER_SECOND", "0"))
    for resolution in ("720p", "1080p", "4k")
}
MARKUP_BPS = int(env("MARKUP_BPS", "15000"))
STORE_FEE_BPS = int(env("STORE_FEE_BPS", "0"))
DAILY_PROVIDER_BUDGET_PAISE = int(env("DAILY_PROVIDER_BUDGET_PAISE", "0"))
BUDGET_SHARDS = 64
MAX_ACTIVE_JOBS = int(env("MAX_ACTIVE_JOBS", "2"))
GENERATION_LEASE_SECONDS = int(env("GENERATION_LEASE_SECONDS", "1200"))
PLAY_ENABLED = flag("PLAY_ENABLED")
PLAY_PACKAGE = env("PLAY_PACKAGE", "com.motionmagic.ai")
PLAY_CREDENTIALS_FILE = env("PLAY_CREDENTIALS_FILE")
BILLING_ENCRYPTION_KEY = env("BILLING_ENCRYPTION_KEY")
if not (0 <= STORE_FEE_BPS < 10000 and 0 <= MARKUP_BPS <= 100000):
    raise ImproperlyConfigured("Invalid markup or fee basis points")
if not (300 <= SESSION_SECONDS <= 2592000):
    raise ImproperlyConfigured("Session duration must be 5 minutes to 30 days")
if not 120 <= GENERATION_LEASE_SECONDS <= 3600:
    raise ImproperlyConfigured("Generation lease must be 120 to 3600 seconds")
if AUTH_ENABLED and not GOOGLE_CLIENT_ID.endswith(".apps.googleusercontent.com"):
    raise ImproperlyConfigured("Configure a Google Web OAuth client ID")
if PLAY_ENABLED and (not PLAY_CREDENTIALS_FILE or not BILLING_ENCRYPTION_KEY):
    raise ImproperlyConfigured("Play Billing requires service credentials and an encryption key")
if GATEWAY_URL and (not GATEWAY_URL.startswith("https://") or not GATEWAY_TOKEN or not S3_BUCKET):
    raise ImproperlyConfigured("The generation gateway requires HTTPS, a server token and an S3 bucket")
if GEMINI_API_KEY and (not GEMINI_BASE_URL.startswith("https://") or not GEMINI_INTERACTIONS_URL.startswith("https://") or not S3_BUCKET):
    raise ImproperlyConfigured("Gemini image generation requires HTTPS and an S3 bucket")
if bool(AWS_ACCESS_KEY_ID) != bool(AWS_SECRET_ACCESS_KEY):
    raise ImproperlyConfigured("Set both MOTION_AI_AWS_ACCESS_KEY_ID and MOTION_AI_AWS_SECRET_ACCESS_KEY")
if not 1024 <= GEMINI_MAX_INPUT_BYTES <= 20 * 1024**2 or not 1024 <= GEMINI_MAX_OUTPUT_BYTES <= 64 * 1024**2:
    raise ImproperlyConfigured("Invalid Gemini input/output byte limit")
if not 1024**2 <= GEMINI_MAX_VIDEO_BYTES <= MAX_UPLOAD_BYTES:
    raise ImproperlyConfigured("Invalid Gemini video byte limit")
if GEMINI_OVERHEAD_PAISE < 0 or any(value < 0 for value in (*GEMINI_RATE_COSTS.values(), *GEMINI_VIDEO_RATE_COSTS.values())):
    raise ImproperlyConfigured("Gemini costs must be non-negative integer paise")
if FAL_OVERHEAD_PAISE < 0 or any(value < 0 for value in FAL_RATE_COSTS.values()):
    raise ImproperlyConfigured("fal costs must be non-negative integer paise")
