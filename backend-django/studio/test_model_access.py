import io
from datetime import timedelta

from django.core.management import call_command
from django.test import TestCase, override_settings
from django.utils import timezone
from rest_framework.test import APIClient

from . import fal_catalog, services
from .authentication import issue_token
from .models import Account, Subscription, Wallet


RUNTIME = dict(FAL_ONLY=True, FAL_KEY="test-fal-key", S3_BUCKET="test-bucket",
               DAILY_PROVIDER_BUDGET_PAISE=1_000_000, BUDGET_SHARDS=1,
               FAL_RATE_COSTS={slug: 5 for slug, *_ in fal_catalog.variants()},
               MARKUP_BPS=90_000, STORE_FEE_BPS=0)


@override_settings(**RUNTIME)
class ModelAccessTests(TestCase):
    def setUp(self):
        call_command("sync_provider_rates", stdout=io.StringIO())
        self.account = Account.objects.create(google_sub="access-free", email="free@example.test", name="Free")
        Wallet.objects.create(account=self.account, balance_paise=10_000)

    def quote(self, slug, **options):
        return services.create_quote(self.account, {"service": slug, "prompt": "A quiet mountain", **options})

    def test_free_can_quote_only_qwen_720p_and_pixverse_720p_silent(self):
        self.assertEqual(self.quote("qwen-image-720p").rate_id, "qwen-image-720p")
        self.assertEqual(self.quote("pixverse-v6-720p-silent", duration=5, generated_audio=False).rate_id,
                         "pixverse-v6-720p-silent")
        for slug, options in (
            ("qwen-image-1080p", {}),
            ("pixverse-v6-720p-audio", {"duration": 5, "generated_audio": True}),
            ("pixverse-v6-1080p-silent", {"duration": 5, "generated_audio": False}),
            ("kling-3-omni-720p-silent", {"duration": 5, "generated_audio": False}),
            ("seedream-5-pro-1k", {}),
        ):
            with self.subTest(slug=slug), self.assertRaisesMessage(Exception, "Upgrade to Creator or Legend"):
                self.quote(slug, **options)

    def test_paid_member_can_use_all_enabled_variants_and_expiry_blocks_new_job(self):
        subscription = Subscription.objects.create(account=self.account, product_id="motion_creator_monthly",
            token_hash="access-paid-token", token_encrypted="test", plan="creator", state="active",
            expires_at=timezone.now() + timedelta(days=1))
        quote = self.quote("gpt-image-2-2k")
        self.assertEqual(quote.rate_id, "gpt-image-2-2k")
        Subscription.objects.filter(pk=subscription.pk).update(state="cancelled")
        self.assertEqual(self.quote("seedream-5-pro-2k").rate_id, "seedream-5-pro-2k")
        Subscription.objects.filter(pk=subscription.pk).update(expires_at=timezone.now() - timedelta(seconds=1))
        with self.assertRaisesMessage(Exception, "Upgrade to Creator or Legend"):
            services.submit_job(self.account, quote.pk, "expired-plan-job")
        self.assertEqual(self.quote("qwen-image-720p").rate_id, "qwen-image-720p")

    def test_topup_balance_does_not_unlock_paid_models(self):
        Wallet.objects.filter(account=self.account).update(topup_balance_paise=10_000, credit_buckets_version=1)
        with self.assertRaisesMessage(Exception, "Upgrade to Creator or Legend"):
            self.quote("gpt-image-2-1k")

    def test_catalog_marks_free_variants_for_the_app(self):
        response = APIClient().get("/api/v1/capabilities")
        self.assertEqual(response.status_code, 200)
        plans = {row["id"]: row["required_plan"] for row in response.json()["cloud"]}
        self.assertEqual({slug for slug, plan in plans.items() if plan == "free"},
                         services.FREE_RATE_SLUGS)
        self.assertEqual(plans["gpt-image-2-2k"], "creator")
