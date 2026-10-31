from datetime import timedelta
from unittest.mock import patch

from django.db import transaction
from django.test import TestCase, override_settings
from django.utils import timezone
from rest_framework.test import APIClient

from . import billing, services
from .authentication import digest, issue_token
from .models import Account, Ledger, Product, Rate, Subscription, Wallet


RUNTIME = dict(PLAY_ENABLED=True, SUBSCRIPTIONS_ENABLED=True,
               BILLING_ENCRYPTION_KEY="MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDA=",
               FAL_ONLY=False, GATEWAY_URL="https://gateway.test", GATEWAY_TOKEN="token",
               S3_BUCKET="bucket", DAILY_PROVIDER_BUDGET_PAISE=100_000,
               BUDGET_SHARDS=1, MARKUP_BPS=90_000, STORE_FEE_BPS=0)


@override_settings(**RUNTIME)
class CreditBucketTests(TestCase):
    def setUp(self):
        self.account = Account.objects.create(google_sub="credit-buckets", email="buckets@example.test", name="Buckets")
        Wallet.objects.create(account=self.account)
        self.product, _ = Product.objects.update_or_create(
            sku="motion_topup_500", defaults={"title": "500 credits", "credit_paise": 500, "enabled": True})
        self.rate = Rate.objects.create(slug="test-image", title="Test image", kind="image", enabled=True,
                                        provider_model="gateway/image", unit_cost_paise=100, max_units=1)
        self.expiry = timezone.now() + timedelta(days=30)

    def receipt(self, order="GPA.month-one", state="SUBSCRIPTION_STATE_ACTIVE", expiry=None):
        return {"subscriptionState": state,
                "externalAccountIdentifiers": {"obfuscatedExternalAccountId": digest(str(self.account.pk))},
                "lineItems": [{"productId": "motion_creator_monthly", "expiryTime": (expiry or self.expiry).isoformat(),
                               "latestSuccessfulOrderId": order, "autoRenewingPlan": {}}]}

    def subscribe(self, order="GPA.month-one", state="SUBSCRIPTION_STATE_ACTIVE", expiry=None):
        with patch("studio.billing.play_subscription_request", return_value=self.receipt(order, state, expiry)), \
             patch("studio.billing.acknowledge_subscription"):
            return billing.verify_subscription(self.account, "motion_creator_monthly", "subscription-token-123456")

    def topup(self):
        purchased = {"purchaseState": 0, "consumptionState": 0, "quantity": 1, "orderId": "GPA.topup-one",
                     "productId": self.product.pk, "obfuscatedExternalAccountId": digest(str(self.account.pk))}
        with patch("studio.billing.play_request", side_effect=lambda sku, token, consume=False: {} if consume else purchased):
            return billing.verify_purchase(self.account, self.product.pk, "topup-token-123456")

    def job(self, cost, key):
        self.rate.unit_cost_paise = cost
        self.rate.save(update_fields=["unit_cost_paise"])
        quote = services.create_quote(self.account, {"service": self.rate.pk, "prompt": "A forest"})
        job, _ = services.submit_job(self.account, quote.pk, key)
        return job

    def wallet(self):
        with transaction.atomic():
            return services.wallet_for_update(self.account)

    def test_monthly_spent_first_renewal_replaces_unused_and_topup_persists(self):
        self.subscribe()
        self.topup()
        first = self.job(100, "first-job")
        self.assertEqual((first.monthly_reserved_paise, first.topup_reserved_paise), (1000, 0))
        services.finish_job(first.pk, "succeeded", 100)
        self.assertEqual((self.wallet().monthly_available_paise, self.wallet().topup_available_paise), (200, 500))

        renewed = self.subscribe("GPA.month-two", expiry=self.expiry + timedelta(days=30))
        self.assertEqual(renewed.latest_order_id, "GPA.month-two")
        self.assertEqual((self.wallet().monthly_available_paise, self.wallet().topup_available_paise), (1200, 500))
        self.assertEqual(Ledger.objects.filter(account=self.account, kind="subscription").count(), 2)
        self.subscribe("GPA.month-two", expiry=self.expiry + timedelta(days=30))
        self.assertEqual(Ledger.objects.filter(account=self.account, kind="subscription").count(), 2)

        second = self.job(140, "second-job")
        self.assertEqual((second.monthly_reserved_paise, second.topup_reserved_paise), (1200, 200))
        services.finish_job(second.pk, "succeeded", 140)
        self.assertEqual((self.wallet().monthly_available_paise, self.wallet().topup_available_paise), (0, 300))
        Wallet.objects.filter(account=self.account).update(monthly_expires_at=timezone.now() - timedelta(seconds=1))
        wallet = self.wallet()
        self.assertEqual((wallet.balance_paise, wallet.monthly_available_paise, wallet.topup_available_paise), (300, 0, 300))

    def test_cancellation_retains_paid_period_then_only_topup_remains(self):
        self.subscribe()
        self.topup()
        self.subscribe(state="SUBSCRIPTION_STATE_CANCELED")
        client = APIClient()
        client.credentials(HTTP_AUTHORIZATION="Bearer " + issue_token(self.account))
        self.assertEqual(client.get("/api/v1/billing/entitlement").json()["plan"], "creator")
        Wallet.objects.filter(account=self.account).update(monthly_expires_at=timezone.now() - timedelta(seconds=1))
        Subscription.objects.filter(account=self.account).update(expires_at=timezone.now() - timedelta(seconds=1))
        self.assertEqual(client.get("/api/v1/billing/entitlement").json()["plan"], "free")
        wallet = client.get("/api/v1/wallet").json()
        self.assertEqual((wallet["monthly_credits"], wallet["topup_credits"], wallet["free_credits"], wallet["available_credits"]), (0, 500, 200, 700))

    def test_monthly_hold_released_after_expiry_does_not_roll_over(self):
        self.subscribe()
        self.topup()
        job = self.job(100, "held-job")
        Wallet.objects.filter(account=self.account).update(monthly_expires_at=timezone.now() - timedelta(seconds=1))
        wallet = self.wallet()
        self.assertEqual((wallet.monthly_balance_paise, wallet.monthly_held_paise, wallet.topup_available_paise), (1000, 1000, 500))
        services.finish_job(job.pk, "failed")
        wallet = self.wallet()
        self.assertEqual((wallet.balance_paise, wallet.monthly_balance_paise, wallet.topup_available_paise), (500, 0, 500))

    def test_renewal_keeps_new_grant_when_old_job_finishes_late(self):
        self.subscribe()
        self.topup()
        job = self.job(100, "renewing-job")
        self.subscribe("GPA.month-two", expiry=self.expiry + timedelta(days=30))
        wallet = self.wallet()
        self.assertEqual((wallet.monthly_available_paise, wallet.topup_available_paise), (1200, 500))
        services.finish_job(job.pk, "failed")
        wallet = self.wallet()
        self.assertEqual((wallet.monthly_available_paise, wallet.topup_available_paise, wallet.balance_paise), (1200, 500, 1700))

    def test_legacy_mixed_wallet_is_separated_without_old_monthly_carryover(self):
        old_key = "subscription:legacy-one"
        current_key = "subscription:legacy-two"
        for kind, reference, change, after in (
            ("subscription", old_key, 1200, 1200),
            ("charge", "job:legacy:settle", -1000, 200),
            ("purchase", "purchase:legacy:grant", 500, 700),
            ("subscription", current_key, 1200, 1900),
        ):
            Ledger.objects.create(account=self.account, kind=kind, reference=reference,
                                  balance_delta=change, held_delta=0, balance_after=after, held_after=0)
        Subscription.objects.create(account=self.account, product_id="motion_creator_monthly",
                                    token_hash=digest("legacy-token"), token_encrypted="legacy",
                                    plan="creator", state="active", expires_at=self.expiry,
                                    last_grant_key=current_key)
        Wallet.objects.filter(account=self.account).update(balance_paise=1900)
        wallet = self.wallet()
        self.assertEqual((wallet.monthly_available_paise, wallet.topup_available_paise, wallet.balance_paise), (1200, 500, 1700))
        self.assertEqual(Ledger.objects.get(reference=f"bucket-migration:{self.account.pk}").balance_delta, -200)

    def test_welcome_credits_are_separate_and_topup_refund_only_reverses_topup(self):
        with transaction.atomic():
            services.grant_welcome_credits(self.account)
        self.topup()
        wallet = self.wallet()
        self.assertEqual((wallet.free_available_paise, wallet.topup_available_paise), (200, 500))
        receipt = {"purchaseState": 1}
        with patch("studio.billing.play_request", return_value=receipt):
            billing.reconcile_purchase(billing.Purchase.objects.get(account=self.account))
        wallet = self.wallet()
        self.assertEqual((wallet.free_available_paise, wallet.topup_available_paise), (200, 0))
