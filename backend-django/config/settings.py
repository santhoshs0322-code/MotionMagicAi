import os
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
ALLOWED_HOSTS = [x.strip() for x in env("ALLOWED_HOSTS", "localhost,127.0.0.1,testserver" if TESTING else "").split(",") if x.strip()]
if not ALLOWED_HOSTS or "*" in ALLOWED_HOSTS:
    raise ImproperlyConfigured("Set explicit MOTION_AI_ALLOWED_HOSTS")
INSTALLED_APPS = ["django.contrib.auth", "django.contrib.contenttypes", "rest_framework", "studio"]
MIDDLEWARE = ["django.middleware.security.SecurityMiddleware", "django.middleware.common.CommonMiddleware", "django.middleware.clickjacking.XFrameOptionsMiddleware", "studio.middleware.ApiHeaders"]
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
# Enable only with private ingress from the ALB security group (it overwrites this header).
BEHIND_ALB = flag("BEHIND_ALB")
if BEHIND_ALB:
    SECURE_PROXY_SSL_HEADER = ("HTTP_X_FORWARDED_PROTO", "https")
CSRF_TRUSTED_ORIGINS = [x for x in env("CSRF_TRUSTED_ORIGINS").split(",") if x]
GOOGLE_CLIENT_ID = env("GOOGLE_CLIENT_ID")
AUTH_ENABLED = flag("AUTH_ENABLED")
SESSION_SECONDS = int(env("SESSION_SECONDS", "86400"))
AWS_REGION = env("AWS_REGION", "ap-south-1")
S3_BUCKET = env("S3_BUCKET")
MAX_UPLOAD_BYTES = int(env("MAX_UPLOAD_BYTES", str(2 * 1024**3)))
GATEWAY_URL = env("GATEWAY_URL").rstrip("/")
GATEWAY_TOKEN = env("GATEWAY_TOKEN")
MARKUP_BPS = int(env("MARKUP_BPS", "15000"))
STORE_FEE_BPS = int(env("STORE_FEE_BPS", "0"))
DAILY_PROVIDER_BUDGET_PAISE = int(env("DAILY_PROVIDER_BUDGET_PAISE", "0"))
BUDGET_SHARDS = 64
MAX_ACTIVE_JOBS = int(env("MAX_ACTIVE_JOBS", "2"))
PLAY_ENABLED = flag("PLAY_ENABLED")
PLAY_PACKAGE = env("PLAY_PACKAGE", "com.motionmagic.ai")
PLAY_CREDENTIALS_FILE = env("PLAY_CREDENTIALS_FILE")
BILLING_ENCRYPTION_KEY = env("BILLING_ENCRYPTION_KEY")
if not (0 <= STORE_FEE_BPS < 10000 and 0 <= MARKUP_BPS <= 100000):
    raise ImproperlyConfigured("Invalid markup or fee basis points")
if not (300 <= SESSION_SECONDS <= 2592000):
    raise ImproperlyConfigured("Session duration must be 5 minutes to 30 days")
if AUTH_ENABLED and not GOOGLE_CLIENT_ID.endswith(".apps.googleusercontent.com"):
    raise ImproperlyConfigured("Configure a Google Web OAuth client ID")
if PLAY_ENABLED and (not PLAY_CREDENTIALS_FILE or not BILLING_ENCRYPTION_KEY):
    raise ImproperlyConfigured("Play Billing requires service credentials and an encryption key")
if GATEWAY_URL and (not GATEWAY_URL.startswith("https://") or not GATEWAY_TOKEN or not S3_BUCKET):
    raise ImproperlyConfigured("The generation gateway requires HTTPS, a server token and an S3 bucket")
