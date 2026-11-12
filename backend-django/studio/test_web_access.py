import base64
from datetime import date
from unittest.mock import patch

from django.contrib.auth import get_user_model
from django.core.cache import cache
from django.test import Client, TestCase, override_settings

from .models import WebVisitorDay


@override_settings(WEB_ENABLED=True, WEB_TEST_GATE_ENABLED=True, WEB_TEST_USERNAME="tester",
    WEB_TEST_PASSWORD="private-testing-password", WEB_VISITOR_TRACKING_ENABLED=True, OWNER_USERNAME="owner")
class WebAccessTests(TestCase):
    def setUp(self):
        cache.clear()
        self.auth = "Basic " + base64.b64encode(b"tester:private-testing-password").decode()

    def test_gate_blocks_browser_until_correct_credentials(self):
        denied = self.client.get("/")
        self.assertEqual(denied.status_code, 401)
        self.assertIn("Basic", denied["WWW-Authenticate"])
        self.assertIn("no-store", denied["Cache-Control"])
        self.assertEqual(self.client.get("/app/session/").status_code, 401)
        self.assertEqual(self.client.get("/app/session/", HTTP_AUTHORIZATION="Basic broken").status_code, 401)
        self.assertEqual(self.client.get("/app/session/", HTTP_AUTHORIZATION=self.auth).status_code, 200)
        self.assertEqual(self.client.get("/", HTTP_AUTHORIZATION=self.auth).status_code, 200)

    def test_native_and_callbacks_are_not_intercepted(self):
        self.assertEqual(self.client.get("/auth/motion-ai/config").status_code, 200)
        with patch("studio.webapp.GatewayReturn.post") as callback:
            from django.http import HttpResponse
            callback.return_value = HttpResponse("OK")
            self.assertEqual(self.client.post("/app/payments/payu/webhook/").status_code, 200)
        self.client.cookies["motion_web_session"] = "old-browser-session"
        self.assertEqual(self.client.get("/api/v1/wallet").status_code, 401)
        # A native bearer token remains native auth even if a browser cookie exists.
        native = self.client.get("/api/v1/wallet", HTTP_AUTHORIZATION="Bearer invalid")
        self.assertNotIn("MotionMagic AI testing", native.get("WWW-Authenticate", ""))

    def test_daily_cookie_deduplication_and_excluded_paths(self):
        with patch("studio.web_access.timezone.localdate", return_value=date(2026, 11, 12)):
            self.client.get("/")
            self.client.get("/app/", HTTP_AUTHORIZATION=self.auth)
            self.client.get("/app/session/", HTTP_AUTHORIZATION=self.auth)
            self.client.get("/ready")
            row = WebVisitorDay.objects.get()
            self.assertEqual((row.views, row.admitted_views), (2, 1))
            Client().get("/")
            self.assertEqual(WebVisitorDay.objects.count(), 2)
        with patch("studio.web_access.timezone.localdate", return_value=date(2026, 11, 13)):
            self.client.get("/")
        self.assertEqual(WebVisitorDay.objects.count(), 3)
        self.assertNotEqual(WebVisitorDay.objects.first().visitor_hash, WebVisitorDay.objects.last().visitor_hash)

    def test_gate_can_be_removed_and_tracking_disabled(self):
        with override_settings(WEB_TEST_GATE_ENABLED=False, WEB_VISITOR_TRACKING_ENABLED=False):
            self.assertEqual(self.client.get("/").status_code, 200)
            self.assertFalse(WebVisitorDay.objects.exists())

    def test_proxy_limit_uses_trusted_client_and_native_header_is_not_reused(self):
        with override_settings(PROXY_MODE="alb"):
            self.assertEqual(self.client.get("/app/session/").status_code, 400)
            for _ in range(30):
                self.client.get("/app/session/", HTTP_X_FORWARDED_FOR="198.51.100.1")
            self.assertEqual(self.client.get("/app/session/", HTTP_X_FORWARDED_FOR="198.51.100.1").status_code, 429)
            self.assertEqual(self.client.get("/app/session/", HTTP_X_FORWARDED_FOR="198.51.100.2").status_code, 401)
        self.client.cookies["motion_web_session"] = "expired-session"
        response = self.client.get("/api/v1/wallet", HTTP_AUTHORIZATION=self.auth)
        self.assertEqual(response.status_code, 401)
        self.assertNotIn(b"Invalid or expired session", response.content)

    def test_visitors_are_owner_only_and_password_guessing_is_limited(self):
        self.assertEqual(self.client.get("/owner/visitors/").status_code, 302)
        users = get_user_model()
        ordinary = users.objects.create_user(username="ordinary", password="not-a-real-secret")
        self.client.force_login(ordinary)
        self.assertEqual(self.client.get("/owner/visitors/").status_code, 403)
        owner = users.objects.create_superuser(username="owner", password="another-test-password")
        self.client.force_login(owner)
        self.assertEqual(self.client.get("/owner/visitors/").status_code, 200)
        for _ in range(30):
            self.client.get("/app/session/")
        self.assertEqual(self.client.get("/app/session/").status_code, 429)
        self.assertEqual(self.client.get("/app/session/", HTTP_AUTHORIZATION=self.auth).status_code, 200)
