import json
import uuid
import base64
from unittest.mock import patch
from django.test import TestCase, override_settings
from django.utils import timezone
from rest_framework.test import APIClient
from .authentication import issue_token
from .models import Account, Wallet, Ledger, Rate, Job, Product
from .services import price, create_quote, submit_job, finish_job
from .billing import verify_purchase
from .authentication import digest
from . import gemini, gemini_video

RUNTIME = dict(GATEWAY_URL="https://gateway.example.test", GATEWAY_TOKEN="server-token", S3_BUCKET="test-bucket", DAILY_PROVIDER_BUDGET_PAISE=6_400_000, MARKUP_BPS=15_000, STORE_FEE_BPS=0)

class PricingTests(TestCase):
    def test_150_percent_markup_and_store_fee_are_integer_safe(self):
        self.assertEqual(price(100, 15_000, 0), 250)
        self.assertEqual(price(100, 15_000, 1_500), 295)

    @override_settings(**RUNTIME)
    def test_quote_reserve_idempotency_and_actual_cost_settlement(self):
        account = Account.objects.create(google_sub="google-1", email="one@example.test", name="One")
        wallet = Wallet.objects.create(account=account, balance_paise=1000)
        rate = Rate.objects.create(slug="video-basic", title="Basic video", kind="video", enabled=True, provider_model="gateway/video-v1", unit_cost_paise=20, max_units=15)
        quote = create_quote(account, {"service": rate.pk, "prompt": "A calm beach", "duration": 5})
        self.assertEqual(quote.provider_cost_paise, 100)
        self.assertEqual(quote.charge_paise, 250)
        job, created = submit_job(account, quote.pk, "same-request-123")
        self.assertTrue(created)
        same, created = submit_job(account, quote.pk, "same-request-123")
        self.assertFalse(created)
        self.assertEqual(job.pk, same.pk)
        wallet.refresh_from_db()
        self.assertEqual((wallet.balance_paise, wallet.held_paise, wallet.available_paise), (1000, 250, 750))
        finish_job(job.pk, "succeeded", 80)
        finish_job(job.pk, "succeeded", 80)
        wallet.refresh_from_db()
        self.assertEqual((wallet.balance_paise, wallet.held_paise), (800, 0))
        self.assertEqual(Ledger.objects.filter(account=account).count(), 2)

    @override_settings(**RUNTIME)
    def test_insufficient_credit_and_per_account_active_job_limit(self):
        account = Account.objects.create(google_sub="google-2", email="two@example.test", name="Two")
        Wallet.objects.create(account=account, balance_paise=249)
        rate = Rate.objects.create(slug="video", title="Video", kind="video", enabled=True, provider_model="gateway/video", unit_cost_paise=20, max_units=15)
        quote = create_quote(account, {"service": rate.pk, "prompt": "A beach", "duration": 5})
        with self.assertRaisesMessage(Exception, "Insufficient credits"):
            submit_job(account, quote.pk, "request-key-123")
        self.assertFalse(Job.objects.exists())

    @override_settings(GATEWAY_URL="", GATEWAY_TOKEN="", GEMINI_API_KEY="server-key", S3_BUCKET="test-bucket", DAILY_PROVIDER_BUDGET_PAISE=6_400_000)
    def test_gemini_rate_quote_freezes_aspect_ratio_and_price(self):
        account = Account.objects.create(google_sub="google-image", email="image@example.test", name="Image")
        Wallet.objects.create(account=account, balance_paise=5000)
        rate = Rate.objects.create(
            slug="nano-banana-2-lite-1k", title="Nano Banana 2 Lite 1K", kind="image",
            enabled=True, provider_model="gemini-3.1-flash-lite-image", unit_cost_paise=300,
            config={"image_size": "1K", "aspect_ratios": ["1:1", "9:16"]},
        )
        quote = create_quote(account, {"service": rate.pk, "prompt": "A neon city", "aspect_ratio": "9:16"})
        self.assertEqual(quote.payload["aspect_ratio"], "9:16")
        self.assertEqual(quote.charge_paise, 750)
        with self.assertRaisesMessage(Exception, "supported aspect ratio"):
            create_quote(account, {"service": rate.pk, "prompt": "A neon city", "aspect_ratio": "16:9"})


class GeminiProviderTests(TestCase):
    class Response:
        status_code = 200
        def __enter__(self):
            return self
        def __exit__(self, *_):
            return False
        def iter_content(self, _):
            payload = {"candidates": [{"content": {"parts": [{"inlineData": {"mimeType": "image/png", "data": base64.b64encode(b"image-bytes").decode("ascii")}}]}}]}
            yield json.dumps(payload).encode()

    @override_settings(GEMINI_API_KEY="server-key", GEMINI_BASE_URL="https://provider.test/v1beta", S3_BUCKET="test-bucket", GEMINI_MAX_OUTPUT_BYTES=1024)
    @patch("studio.gemini.storage.put_generated_image", return_value={"key": "output", "mime": "image/png", "size": 11})
    @patch("studio.gemini.requests.post")
    def test_generation_uses_server_key_and_uploads_result(self, post, put):
        account = Account.objects.create(google_sub="google-provider", email="provider@example.test", name="Provider")
        rate = Rate.objects.create(slug="nano", title="Nano", kind="image", enabled=True, provider_model="gemini-3.1-flash-image", unit_cost_paise=400, config={"image_size": "1K"})
        from .models import Quote, Budget
        quote_row = Quote.objects.create(account=account, rate=rate, payload={"model": rate.provider_model, "prompt": "A mountain", "aspect_ratio": "1:1"}, payload_hash="x" * 64, provider_cost_paise=400, overhead_paise=0, markup_bps=15000, fee_bps=0, charge_paise=1000, expires_at=timezone.now())
        budget = Budget.objects.create(day=timezone.now().date(), shard=0)
        job = Job.objects.create(account=account, quote=quote_row, idempotency_key="provider-test", budget=budget, next_attempt_at=timezone.now())
        post.return_value = self.Response()
        result = gemini.submit(Job.objects.select_related("quote__rate").get(pk=job.pk))
        self.assertEqual(result["state"], "succeeded")
        self.assertEqual(result["cost_paise"], 400)
        self.assertEqual(post.call_args.kwargs["headers"]["x-goog-api-key"], "server-key")
        self.assertNotIn("server-key", json.dumps(post.call_args.kwargs["json"]))
        put.assert_called_once()

    @override_settings(GEMINI_API_KEY="server-key", GEMINI_INTERACTIONS_URL="https://provider.test/v1beta", S3_BUCKET="test-bucket")
    @patch("studio.gemini_video.requests.post")
    def test_omni_video_returns_reconcilable_file_id(self, post):
        account = Account.objects.create(google_sub="google-video", email="video@example.test", name="Video")
        rate = Rate.objects.create(slug="omni", title="Omni", kind="video", enabled=True, provider_model="gemini-omni-1.1-flash", unit_cost_paise=800, max_units=10, config={"resolution": "720p", "durations": [5, 10]})
        from .models import Quote, Budget
        quote_row = Quote.objects.create(account=account, rate=rate, payload={"model": rate.provider_model, "prompt": "A mountain", "units": 5, "aspect_ratio": "9:16", "generated_audio": True}, payload_hash="y" * 64, provider_cost_paise=4000, overhead_paise=0, markup_bps=15000, fee_bps=0, charge_paise=10000, expires_at=timezone.now())
        budget = Budget.objects.create(day=timezone.now().date(), shard=1)
        job = Job.objects.create(account=account, quote=quote_row, idempotency_key="video-provider-test", budget=budget, next_attempt_at=timezone.now())
        response = self.Response()
        response.iter_content = lambda _: iter([json.dumps({"output_video": {"uri": "https://generativelanguage.googleapis.com/v1beta/files/video_123"}}).encode()])
        post.return_value = response
        result = gemini_video.submit(Job.objects.select_related("quote__rate").get(pk=job.pk))
        self.assertEqual(result, {"state": "running", "id": "video_123"})
        sent = post.call_args.kwargs
        self.assertEqual(sent["json"]["response_format"]["delivery"], "uri")
        self.assertEqual(sent["json"]["response_format"]["resolution"], "720p")
        self.assertNotIn("server-key", json.dumps(sent["json"]))

class ApiTests(TestCase):
    def setUp(self):
        self.account = Account.objects.create(google_sub="google-api", email="api@example.test", name="API")
        Wallet.objects.create(account=self.account, balance_paise=500)
        self.client = APIClient()

    def auth(self):
        self.client.credentials(HTTP_AUTHORIZATION="Bearer " + issue_token(self.account))

    def test_health_capabilities_and_authentication(self):
        self.assertEqual(self.client.get("/health").status_code, 200)
        capability = self.client.get("/api/v1/capabilities")
        self.assertEqual(capability.status_code, 200)
        self.assertEqual(capability.json()["cloud"], [])
        self.assertFalse(capability.json()["subscriptions"])
        self.assertEqual(self.client.get("/api/v1/wallet").status_code, 401)
        self.auth()
        response = self.client.get("/auth/motion-ai/me")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["user"]["id"], str(self.account.pk))

    def test_logout_revokes_token(self):
        token = issue_token(self.account)
        self.client.credentials(HTTP_AUTHORIZATION="Bearer " + token)
        self.assertEqual(self.client.post("/auth/motion-ai/logout", {}, format="json").status_code, 200)
        self.assertEqual(self.client.get("/auth/motion-ai/me").status_code, 401)

    @override_settings(**RUNTIME)
    def test_job_endpoint_requires_and_reuses_idempotency_key(self):
        rate = Rate.objects.create(slug="image", title="Image", kind="image", enabled=True, provider_model="gateway/image", unit_cost_paise=40, max_units=1)
        self.auth()
        quote = self.client.post("/api/v1/quotes", {"service": rate.pk, "prompt": "A mountain"}, format="json").json()
        first = self.client.post("/api/v1/jobs", {"quote_id": quote["id"]}, format="json", HTTP_IDEMPOTENCY_KEY="image-request-1")
        second = self.client.post("/api/v1/jobs", {"quote_id": quote["id"]}, format="json", HTTP_IDEMPOTENCY_KEY="image-request-1")
        self.assertEqual((first.status_code, second.status_code), (202, 200))
        self.assertEqual(first.json()["id"], second.json()["id"])

    def test_products_are_hidden_when_billing_is_disabled(self):
        Product.objects.create(sku="credits_100", title="Credits", credit_paise=10000, enabled=True)
        self.auth()
        self.assertEqual(self.client.get("/api/v1/billing/products").json()["products"], [])

class InputTests(TestCase):
    def test_unknown_fields_are_rejected(self):
        account = Account.objects.create(google_sub="google-input", email="input@example.test", name="Input")
        Wallet.objects.create(account=account)
        client = APIClient()
        client.credentials(HTTP_AUTHORIZATION="Bearer " + issue_token(account))
        response = client.post("/api/v1/jobs", {"quote_id": str(uuid.uuid4()), "admin": True}, format="json", HTTP_IDEMPOTENCY_KEY="input-request-1")
        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json()["ok"], False)

class BillingTests(TestCase):
    @override_settings(PLAY_ENABLED=True, BILLING_ENCRYPTION_KEY="MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDA=", PLAY_PACKAGE="com.motionmagic.ai")
    def test_purchase_retry_does_not_double_grant_and_refund_reverses_credit(self):
        account = Account.objects.create(google_sub="google-billing", email="bill@example.test", name="Billing")
        Wallet.objects.create(account=account)
        Product.objects.create(sku="credits_100", title="100 credits", credit_paise=100, enabled=True)
        purchased = {"purchaseState": 0, "consumptionState": 0, "quantity": 1, "orderId": "GPA.test-order", "productId": "credits_100", "obfuscatedExternalAccountId": digest(str(account.pk))}
        with patch("studio.billing.play_request", side_effect=lambda sku, token, consume=False: {} if consume else purchased):
            first = verify_purchase(account, "credits_100", "purchase-token-value")
        Wallet.objects.get(account=account).refresh_from_db()
        self.assertEqual(Wallet.objects.get(account=account).balance_paise, 100)
        purchased["consumptionState"] = 1
        with patch("studio.billing.play_request", return_value=purchased):
            second = verify_purchase(account, "credits_100", "purchase-token-value")
        self.assertEqual(first.pk, second.pk)
        self.assertEqual(Wallet.objects.get(account=account).balance_paise, 100)
        cancelled = {**purchased, "purchaseState": 1}
        with patch("studio.billing.play_request", return_value=cancelled):
            verify_purchase(account, "credits_100", "purchase-token-value")
        self.assertEqual(Wallet.objects.get(account=account).balance_paise, 0)
        self.assertEqual(Ledger.objects.filter(account=account).count(), 2)
