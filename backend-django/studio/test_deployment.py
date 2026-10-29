import os
from pathlib import Path
import runpy
from unittest.mock import call, patch

from django.core.exceptions import ImproperlyConfigured
from django.test import SimpleTestCase, TestCase, override_settings
from rest_framework.test import APIClient


class DeploymentSettingsTests(SimpleTestCase):
    def load(self, **values):
        environment = {
            "MOTION_AI_DJANGO_SECRET": "django-unit-test-secret-" + "a" * 40,
            "MOTION_AI_JWT_SECRET": "jwt-unit-test-secret-" + "b" * 40,
            "MOTION_AI_DATABASE_URL": "postgresql://test:test@database.test/motion_ai",
            "MOTION_AI_REDIS_URL": "redis://cache.test:6379/0",
            **values,
        }
        with patch.dict(os.environ, environment, clear=True):
            return runpy.run_path(str(Path(__file__).resolve().parents[1] / "config/settings.py"))

    def test_render_uses_exact_platform_host_and_https_without_wildcards(self):
        config = self.load(MOTION_AI_PROXY_MODE="render", RENDER_EXTERNAL_HOSTNAME="motionmagic-123.onrender.com")
        self.assertEqual(config["ALLOWED_HOSTS"], ["motionmagic-123.onrender.com"])
        self.assertEqual(config["CSRF_TRUSTED_ORIGINS"], ["https://motionmagic-123.onrender.com"])
        self.assertEqual(config["SECURE_PROXY_SSL_HEADER"], ("HTTP_X_FORWARDED_PROTO", "https"))
        self.assertTrue(config["SECURE_SSL_REDIRECT"])
        self.assertFalse(config["TESTING"])
        self.assertFalse(config["AUTH_ENABLED"])
        self.assertFalse(config["PLAY_ENABLED"])

    def test_render_preserves_custom_domain_and_rejects_invalid_platform_hosts(self):
        config = self.load(MOTION_AI_PROXY_MODE="render", RENDER_EXTERNAL_HOSTNAME="motionmagic.onrender.com",
                           MOTION_AI_ALLOWED_HOSTS="api.example.test", MOTION_AI_CSRF_TRUSTED_ORIGINS="https://api.example.test")
        self.assertIn("api.example.test", config["ALLOWED_HOSTS"])
        self.assertIn("https://api.example.test", config["CSRF_TRUSTED_ORIGINS"])
        for host in ("", "*.onrender.com", "https://motionmagic.onrender.com", "evil.example", "motionmagic.onrender.com.evil.example"):
            with self.subTest(host=host), self.assertRaises(ImproperlyConfigured):
                self.load(MOTION_AI_PROXY_MODE="render", RENDER_EXTERNAL_HOSTNAME=host)

    def test_legacy_aws_proxy_setting_and_direct_mode(self):
        config = self.load(MOTION_AI_BEHIND_ALB="true", MOTION_AI_ALLOWED_HOSTS="api.example.test")
        self.assertEqual(config["PROXY_MODE"], "alb")
        self.assertIn("SECURE_PROXY_SSL_HEADER", config)
        direct = self.load(MOTION_AI_PROXY_MODE="none", MOTION_AI_ALLOWED_HOSTS="localhost")
        self.assertNotIn("SECURE_PROXY_SSL_HEADER", direct)
        with self.assertRaises(ImproperlyConfigured):
            self.load(MOTION_AI_PROXY_MODE="unknown", MOTION_AI_ALLOWED_HOSTS="api.example.test")

    def test_production_still_requires_database_cache_hosts_and_separate_secrets(self):
        for changed in (
            {"MOTION_AI_DATABASE_URL": "sqlite:///:memory:"},
            {"MOTION_AI_REDIS_URL": ""},
            {"MOTION_AI_ALLOWED_HOSTS": "*"},
            {"MOTION_AI_JWT_SECRET": "short"},
        ):
            with self.subTest(changed=list(changed)), self.assertRaises(ImproperlyConfigured):
                self.load(**{"MOTION_AI_ALLOWED_HOSTS": "api.example.test", **changed})

    def test_gunicorn_reads_platform_port_and_prefixed_worker_settings(self):
        with patch.dict(os.environ, {"PORT": "10000", "MOTION_AI_WEB_WORKERS": "1", "MOTION_AI_WEB_THREADS": "4"}, clear=True):
            config = runpy.run_path(str(Path(__file__).resolve().parents[1] / "config/gunicorn.conf.py"))
        self.assertEqual(config["bind"], "0.0.0.0:10000")
        self.assertEqual((config["workers"], config["threads"]), (1, 4))


class ProxyRequestTests(SimpleTestCase):
    @override_settings(SECURE_SSL_REDIRECT=True, SECURE_PROXY_SSL_HEADER=("HTTP_X_FORWARDED_PROTO", "https"))
    def test_https_edge_request_does_not_redirect_forever(self):
        client = APIClient()
        self.assertEqual(client.get("/health", HTTP_X_FORWARDED_PROTO="https").status_code, 200)
        self.assertEqual(client.get("/health", HTTP_X_FORWARDED_PROTO="http").status_code, 301)

    @override_settings(AUTH_ENABLED=True)
    def test_login_throttle_ignores_spoofed_prefix_and_rejects_invalid_proxy_routing(self):
        client = APIClient(enforce_csrf_checks=True)
        for mode in ("render", "alb"):
            with self.subTest(mode=mode), override_settings(PROXY_MODE=mode), \
                    patch("studio.authentication.rate_limit") as limit, \
                    patch("studio.authentication.verify_google", side_effect=ValueError):
                for prefix in ("203.0.113.10", "203.0.113.99"):
                    response = client.post("/auth/motion-ai/google/native", {"app": "motionmagic", "idToken": "x" * 25},
                                           format="json", HTTP_X_FORWARDED_FOR=prefix + ", 198.51.100.7")
                    self.assertEqual(response.status_code, 401)
                self.assertEqual(limit.call_args_list, [call("login:198.51.100.7", 30)] * 2)
                limit.reset_mock()
                response = client.post("/auth/motion-ai/google/native", {}, format="json", HTTP_X_FORWARDED_FOR="invalid")
                self.assertEqual(response.status_code, 400)
                limit.assert_not_called()


class ReadinessTests(TestCase):
    def test_cache_outage_fails_readiness_but_not_process_liveness(self):
        client = APIClient()
        with patch("studio.views.cache.set", side_effect=ConnectionError("private diagnostic")):
            self.assertEqual(client.get("/health").status_code, 200)
            response = client.get("/ready")
        self.assertEqual(response.status_code, 503)
        self.assertNotIn("private diagnostic", response.content.decode())
