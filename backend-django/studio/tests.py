import json
import uuid
from unittest.mock import patch
from django.test import TestCase, override_settings
from django.utils import timezone
from rest_framework.test import APIClient
from .authentication import issue_token
from .models import Account, Wallet, Ledger, Rate, Job, Product
from .services import price, create_quote, submit_job, finish_job
from .billing import verify_purchase
from .authentication import digest

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
