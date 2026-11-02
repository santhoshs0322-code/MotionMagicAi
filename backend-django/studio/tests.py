import json
import uuid
import base64
from datetime import timedelta
from unittest.mock import patch
from django.test import TestCase, override_settings
from django.db import transaction
from django.utils import timezone
from rest_framework.test import APIClient
from .authentication import issue_token
from .models import Account, Wallet, Ledger, Rate, Job, Asset, Quote, Budget, Product, RefreshSession, Subscription
from .services import price, create_quote, submit_job, finish_job
from .services import grant_welcome_credits
from .billing import verify_purchase
from .authentication import digest
from . import gemini, gemini_video

RUNTIME = dict(FAL_ONLY=False, GATEWAY_URL="https://gateway.example.test", GATEWAY_TOKEN="server-token", S3_BUCKET="test-bucket", DAILY_PROVIDER_BUDGET_PAISE=6_400_000, MARKUP_BPS=15_000, STORE_FEE_BPS=0)

def paid_account(account):
    Subscription.objects.create(account=account, product_id="motion_creator_monthly", token_hash=digest(str(account.pk)),
                                token_encrypted="test", plan="creator", state="active",
                                expires_at=timezone.now() + timedelta(days=1))

class PricingTests(TestCase):
    def test_credit_only_quote_targets_25_percent_after_assumed_play_fee(self):
        # 10 seconds of Kling O3 Pro with audio: $1.40 fal = 140 USD cents.
        charge = price(140, 16_667, 1_500)
        self.assertEqual(charge, 440)
        # 200 customer credits equal $1; the provider spends 280 credit-equivalents.
        self.assertGreaterEqual((charge * 85 // 100 - 280) * 4, charge * 85 // 100)

    def test_150_percent_markup_and_store_fee_are_integer_safe(self):
        self.assertEqual(price(100, 15_000, 0), 250)
        self.assertEqual(price(100, 15_000, 1_500), 295)

    @override_settings(**RUNTIME)
    def test_quote_reserve_idempotency_and_actual_cost_settlement(self):
        account = Account.objects.create(google_sub="google-1", email="one@example.test", name="One")
        paid_account(account)
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
        paid_account(account)
        Wallet.objects.create(account=account, balance_paise=249)
        rate = Rate.objects.create(slug="video", title="Video", kind="video", enabled=True, provider_model="gateway/video", unit_cost_paise=20, max_units=15)
        quote = create_quote(account, {"service": rate.pk, "prompt": "A beach", "duration": 5})
        with self.assertRaisesMessage(Exception, "Insufficient credits"):
            submit_job(account, quote.pk, "request-key-123")
        self.assertFalse(Job.objects.exists())

    @override_settings(**RUNTIME, MAX_ACTIVE_JOBS=3)
    def test_three_parallel_generations_and_fourth_waits_for_a_slot(self):
        account = Account.objects.create(google_sub="parallel-3", email="parallel@example.test", name="Three")
        paid_account(account)
        Wallet.objects.create(account=account, balance_paise=10_000)
        rate = Rate.objects.create(slug="parallel-video", title="Video", kind="video", enabled=True,
                                   provider_model="gateway/video", unit_cost_paise=20, max_units=15)
        quotes = [create_quote(account, {"service": rate.pk, "prompt": f"Video {i}", "duration": 5}) for i in range(4)]
        jobs = [submit_job(account, quotes[i].pk, f"parallel-request-{i}")[0] for i in range(3)]
        self.assertEqual(Job.objects.filter(account=account).exclude(state__in=("succeeded", "failed", "cancelled")).count(), 3)
        with self.assertRaisesMessage(Exception, "up to 3 generations"):
            submit_job(account, quotes[3].pk, "parallel-request-3")
        self.assertEqual(submit_job(account, quotes[0].pk, "parallel-request-0")[0].pk, jobs[0].pk)
        finish_job(jobs[0].pk, "failed", 0)
        self.assertTrue(submit_job(account, quotes[3].pk, "parallel-request-3")[1])

    @override_settings(FAL_ONLY=False, GATEWAY_URL="", GATEWAY_TOKEN="", GEMINI_API_KEY="server-key", S3_BUCKET="test-bucket", DAILY_PROVIDER_BUDGET_PAISE=6_400_000)
    def test_gemini_rate_quote_freezes_aspect_ratio_and_price(self):
        account = Account.objects.create(google_sub="google-image", email="image@example.test", name="Image")
        paid_account(account)
        Wallet.objects.create(account=account, balance_paise=5000)
        rate = Rate.objects.create(
            slug="nano-banana-2-lite-1k", title="Nano Banana 2 Lite 1K", kind="image",
            enabled=True, provider_model="gemini-3.1-flash-lite-image", unit_cost_paise=300,
            config={"image_size": "1K", "aspect_ratios": ["1:1", "9:16"]},
        )
        quote = create_quote(account, {"service": rate.pk, "prompt": "A neon city", "aspect_ratio": "9:16"})
        self.assertEqual(quote.payload["aspect_ratio"], "9:16")
        self.assertEqual(quote.charge_paise, 942)
        with self.assertRaisesMessage(Exception, "supported aspect ratio"):
            create_quote(account, {"service": rate.pk, "prompt": "A neon city", "aspect_ratio": "16:9"})


class WelcomeCreditTests(TestCase):
    @override_settings(WELCOME_CREDITS=100)
    def test_verified_account_welcome_credit_is_idempotent(self):
        account = Account.objects.create(google_sub="welcome-google", email="welcome@example.test", name="Welcome")
        with transaction.atomic():
            grant_welcome_credits(account)
            grant_welcome_credits(account)
        wallet = Wallet.objects.get(account=account)
        self.assertEqual(wallet.balance_paise, 100)
        self.assertEqual(Ledger.objects.filter(account=account, kind="welcome").count(), 1)

    @override_settings(AUTH_ENABLED=True, WELCOME_CREDITS=200)
    @patch("studio.authentication.verify_google")
    def test_existing_google_account_receives_missing_welcome_once(self, verify_google):
        account = Account.objects.create(google_sub="existing-google", email="existing@example.test", name="Existing")
        Wallet.objects.create(account=account)
        verify_google.return_value = {"sub": account.google_sub, "email": account.email, "name": account.name}
        client = APIClient()
        for _ in range(2):
            response = client.post("/auth/motion-ai/google/native", {"app": "motionmagic", "idToken": "google-id-token-for-testing"}, format="json")
            self.assertEqual(response.status_code, 200)
            self.assertTrue(response.json()["refreshToken"])
        self.assertEqual(Wallet.objects.get(account=account).balance_paise, 200)
        self.assertEqual(Ledger.objects.filter(account=account, kind="welcome").count(), 1)

    @override_settings(WELCOME_CREDITS=200)
    def test_existing_wallet_receives_missing_credits_without_new_login(self):
        account = Account.objects.create(google_sub="older-google", email="older@example.test", name="Older")
        Wallet.objects.create(account=account)
        client = APIClient()
        client.credentials(HTTP_AUTHORIZATION="Bearer " + issue_token(account))
        self.assertEqual(client.get("/api/v1/wallet").json()["balance_credits"], 200)
        self.assertEqual(client.get("/api/v1/wallet").json()["balance_credits"], 200)


class AccountIsolationTests(TestCase):
    @override_settings(WELCOME_CREDITS=200)
    def test_each_user_gets_own_credits_and_cannot_read_another_users_media_or_jobs(self):
        first = Account.objects.create(google_sub="isolation-first", email="first@example.test", name="First")
        second = Account.objects.create(google_sub="isolation-second", email="second@example.test", name="Second")
        asset = Asset.objects.create(account=first, key=f"accounts/{first.pk}/outputs/private.png", mime="image/png", size=12, ready=True)
        rate = Rate.objects.create(slug="isolation-image", title="Image", kind="image", provider_model="test/image", unit_cost_paise=1)
        quote = Quote.objects.create(account=first, rate=rate, payload={"prompt": "Private image", "kind": "image"},
            payload_hash="a" * 64, provider_cost_paise=1, overhead_paise=0, markup_bps=0, fee_bps=0,
            charge_paise=1, expires_at=timezone.now() + timedelta(minutes=5))
        budget = Budget.objects.create(day=timezone.now().date(), shard=0)
        job = Job.objects.create(account=first, quote=quote, idempotency_key="isolation-job", budget=budget,
            output=asset, state="succeeded", next_attempt_at=timezone.now())
        client = APIClient()
        client.credentials(HTTP_AUTHORIZATION="Bearer " + issue_token(first))
        self.assertEqual(client.get("/api/v1/wallet").json()["balance_credits"], 200)
        self.assertEqual(client.get("/api/v1/assets").json()["results"][0]["id"], str(asset.pk))
        self.assertEqual(client.get("/api/v1/jobs").json()["results"][0]["id"], str(job.pk))
        client.credentials(HTTP_AUTHORIZATION="Bearer " + issue_token(second))
        self.assertEqual(client.get("/api/v1/wallet").json()["balance_credits"], 200)
        self.assertEqual(client.get("/api/v1/assets").json()["results"], [])
        self.assertEqual(client.get("/api/v1/jobs").json()["results"], [])
        self.assertEqual(client.get(f"/api/v1/assets/{asset.pk}").status_code, 404)
        self.assertEqual(client.get(f"/api/v1/jobs/{job.pk}").status_code, 404)
        self.assertEqual(len(client.get("/api/v1/transactions").json()["results"]), 1)
        self.assertEqual(Wallet.objects.get(account=first).balance_paise, 200)
        self.assertEqual(Wallet.objects.get(account=second).balance_paise, 200)
        Asset.objects.filter(pk=asset.pk).update(key=f"accounts/{second.pk}/outputs/private.png")
        client.credentials(HTTP_AUTHORIZATION="Bearer " + issue_token(first))
        self.assertEqual(client.get(f"/api/v1/assets/{asset.pk}").status_code, 503)
        foreign = Asset.objects.create(account=second, key=f"accounts/{second.pk}/outputs/foreign.png", mime="image/png", size=12, ready=True)
        unfinished_quote = Quote.objects.create(account=first, rate=rate, payload={"prompt": "Own image", "kind": "image"},
            payload_hash="b" * 64, provider_cost_paise=1, overhead_paise=0, markup_bps=0, fee_bps=0,
            charge_paise=1, expires_at=timezone.now() + timedelta(minutes=5))
        unfinished = Job.objects.create(account=first, quote=unfinished_quote, idempotency_key="unfinished-job",
            budget=budget, next_attempt_at=timezone.now())
        with self.assertRaisesMessage(Exception, "does not belong to this account"):
            finish_job(unfinished.pk, "succeeded", 1, asset=foreign)
        unfinished.refresh_from_db()
        self.assertEqual(unfinished.state, "queued")
        self.assertEqual(Wallet.objects.get(account=first).balance_paise, 200)


class RefreshSessionTests(TestCase):
    def test_refresh_rotates_and_can_be_revoked_with_expired_access(self):
        from .authentication import issue_refresh
        account = Account.objects.create(google_sub="refresh-google", email="refresh@example.test", name="Refresh")
        first = issue_refresh(account)
        client = APIClient()
        response = client.post("/auth/motion-ai/refresh", {"refreshToken": first}, format="json")
        self.assertEqual(response.status_code, 200)
        second = response.json()["refreshToken"]
        self.assertNotEqual(first, second)
        self.assertEqual(client.post("/auth/motion-ai/refresh", {"refreshToken": first}, format="json").status_code, 401)
        self.assertEqual(client.post("/auth/motion-ai/revoke", {"refreshToken": second}, format="json").status_code, 200)
        self.assertEqual(client.post("/auth/motion-ai/refresh", {"refreshToken": second}, format="json").status_code, 401)

    def test_expired_refresh_cannot_renew(self):
        from .authentication import issue_refresh
        from datetime import timedelta
        account = Account.objects.create(google_sub="expired-google", email="expired@example.test", name="Expired")
        token = issue_refresh(account)
        RefreshSession.objects.filter(token_hash=digest(token)).update(expires_at=timezone.now() - timedelta(seconds=1))
        self.assertEqual(APIClient().post("/auth/motion-ai/refresh", {"refreshToken": token}, format="json").status_code, 401)


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

    @override_settings(**RUNTIME)
    def test_capabilities_include_exact_credit_prices_for_each_video_duration(self):
        Rate.objects.create(slug="video-prices", title="Video", kind="video", enabled=True,
                            provider_model="gateway/video", unit_cost_paise=20, overhead_paise=3,
                            max_units=10, config={"durations": [5, 10], "aspect_ratios": ["9:16"]})
        rates = self.client.get("/api/v1/capabilities").json()["cloud"]
        item = next(row for row in rates if row["id"] == "video-prices")
        self.assertEqual(item["credit_prices"], {"5": price(103), "10": price(203)})

    def test_logout_revokes_token(self):
        token = issue_token(self.account)
        self.client.credentials(HTTP_AUTHORIZATION="Bearer " + token)
        self.assertEqual(self.client.post("/auth/motion-ai/logout", {}, format="json").status_code, 200)
        self.assertEqual(self.client.get("/auth/motion-ai/me").status_code, 401)

    @override_settings(**RUNTIME)
    def test_job_endpoint_requires_and_reuses_idempotency_key(self):
        paid_account(self.account)
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

    def test_active_job_list_is_server_filtered_and_account_scoped(self):
        other = Account.objects.create(google_sub="other-jobs", email="other-jobs@example.test", name="Other")
        rate = Rate.objects.create(slug="job-list-test", title="Test", kind="image", enabled=True,
                                   provider_model="gateway/image", unit_cost_paise=1, max_units=1)
        budget = Budget.objects.create(day=timezone.now().date(), shard=0)
        def make_job(account, state, key):
            quote = Quote.objects.create(account=account, rate=rate, payload={"prompt": key, "kind": "image"},
                                         payload_hash=key, provider_cost_paise=1, overhead_paise=0,
                                         markup_bps=0, fee_bps=0, charge_paise=2,
                                         expires_at=timezone.now() + timedelta(minutes=5))
            return Job.objects.create(account=account, quote=quote, budget=budget, state=state,
                                      idempotency_key=key, next_attempt_at=timezone.now())
        own_active = make_job(self.account, "running", "own-active")
        own_done = make_job(self.account, "succeeded", "own-done")
        other_active = make_job(other, "running", "other-active")
        self.auth()
        active = self.client.get("/api/v1/jobs?scope=active")
        self.assertEqual(active.status_code, 200)
        self.assertEqual([row["id"] for row in active.json()["results"]], [str(own_active.pk)])
        recent_ids = {row["id"] for row in self.client.get("/api/v1/jobs?scope=recent").json()["results"]}
        self.assertEqual(recent_ids, {str(own_active.pk), str(own_done.pk)})
        self.assertEqual(self.client.get(f"/api/v1/jobs/{other_active.pk}").status_code, 404)
        self.assertEqual(self.client.get("/api/v1/jobs?scope=all").status_code, 400)

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
    @override_settings(SUBSCRIPTIONS_ENABLED=False)
    def test_credit_only_mode_rejects_new_subscription_purchase(self):
        from .billing import verify_subscription
        account = Account.objects.create(google_sub="credit-only", email="credit-only@example.test", name="Credit")
        with self.assertRaisesMessage(Exception, "New subscriptions are not available"):
            verify_subscription(account, "motion_creator_monthly", "new-subscription-token")

    @override_settings(PLAY_ENABLED=True, BILLING_ENCRYPTION_KEY="MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDA=", PLAY_PACKAGE="com.gamixtv.MotionMagicAI")
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
