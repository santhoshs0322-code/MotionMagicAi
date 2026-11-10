import io
from unittest.mock import patch

from django.core.management import call_command
from django.test import TestCase, override_settings
from rest_framework.test import APIClient

from . import billing
from .authentication import digest
from .errors import Problem
from .models import Account, Ledger, Product, Wallet


@override_settings(PLAY_ENABLED=True, ENABLE_PACK_20000=True,
    BILLING_ENCRYPTION_KEY="MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDA=")
class CreditPackCatalogTests(TestCase):
    def setUp(self):
        self.account = Account.objects.create(google_sub="pack-catalog", email="packs@example.test", name="Packs")
        Wallet.objects.create(account=self.account, credit_buckets_version=1)
        self.client = APIClient()
        self.client.force_authenticate(user=self.account)
        self.product = Product.objects.get(pk="motion_pack_20000")

    def receipt(self, sku, order="GPA.pack-test"):
        return dict(purchaseState=0, consumptionState=0, quantity=1, orderId=order,
            productId=sku, obfuscatedExternalAccountId=digest(str(self.account.pk)))

    @override_settings(ENABLE_TOPUPS=True, ENABLE_CREDIT_PACKS=True, ENABLE_PACK_10000=True)
    @patch("studio.management.commands.sync_provider_rates.call_command")
    def test_sync_retires_500_even_with_legacy_flags_and_enables_20000(self, nested):
        Product.objects.filter(pk__in=billing.RETIRED_CREDIT_SKUS).update(enabled=True)
        call_command("sync_provider_rates", stdout=io.StringIO())
        self.assertFalse(Product.objects.filter(pk__in=billing.RETIRED_CREDIT_SKUS, enabled=True).exists())
        self.product.refresh_from_db()
        self.assertEqual((self.product.credit_paise, self.product.enabled), (20000, True))
        self.assertTrue(Product.objects.get(pk="motion_pack_10000").enabled)

    def test_public_catalog_hides_retired_products_even_if_old_server_reenables_them(self):
        Product.objects.filter(pk__in=billing.RETIRED_CREDIT_SKUS).update(enabled=True)
        self.product.enabled = True
        self.product.save()
        products = self.client.get("/api/v1/billing/products").json()["products"]
        skus = {row["sku"] for row in products}
        self.assertTrue(skus.isdisjoint(billing.RETIRED_CREDIT_SKUS))
        self.assertEqual(next(row["credits"] for row in products if row["sku"] == self.product.pk), 20000)

    @patch("studio.billing.play_request")
    def test_20000_must_be_enabled_before_verification(self, play):
        with self.assertRaises(Problem): billing.verify_purchase(self.account, self.product.pk, "test-purchase-token")
        play.assert_not_called()

    def test_20000_is_granted_once_and_refunded_once(self):
        self.product.enabled = True
        self.product.save()
        receipt = self.receipt(self.product.pk)
        with patch("studio.billing.play_request", side_effect=lambda sku, token, consume=False: {} if consume else receipt):
            first = billing.verify_purchase(self.account, self.product.pk, "purchase-token-20000")
            receipt["consumptionState"] = 1
            second = billing.verify_purchase(self.account, self.product.pk, "purchase-token-20000")
            self.assertEqual(first.pk, second.pk)
            wallet = Wallet.objects.get(account=self.account)
            self.assertEqual((wallet.balance_paise, wallet.topup_balance_paise), (20000, 20000))
            self.assertEqual(Ledger.objects.filter(account=self.account, kind="purchase").count(), 1)
            receipt["purchaseState"] = 1
            billing.verify_purchase(self.account, self.product.pk, "purchase-token-20000")
            billing.verify_purchase(self.account, self.product.pk, "purchase-token-20000")
        wallet.refresh_from_db()
        self.assertEqual((wallet.balance_paise, wallet.topup_balance_paise), (0, 0))
        self.assertEqual(Ledger.objects.filter(account=self.account, kind="refund").count(), 1)

    def test_retired_500_recovers_real_receipt_without_allowing_foreign_account(self):
        sku = "motion_credits_500_k7p4"
        Product.objects.filter(pk=sku).update(enabled=False)
        receipt = self.receipt(sku)
        with patch("studio.billing.play_request", side_effect=lambda sku, token, consume=False: {} if consume else receipt):
            purchase = billing.verify_purchase(self.account, sku, "legacy-purchase-token")
            self.assertEqual(purchase.credit_paise, 500)
            other = Account.objects.create(google_sub="other-pack", email="other@example.test", name="Other")
            with self.assertRaises(Problem): billing.verify_purchase(other, sku, "legacy-purchase-token")
        self.assertEqual(Wallet.objects.get(account=self.account).balance_paise, 500)

    @patch("studio.billing.play_request")
    def test_unrelated_disabled_pack_cannot_use_legacy_recovery(self, play):
        Product.objects.create(sku="disabled-unrelated", title="Disabled", credit_paise=500, enabled=False)
        with self.assertRaises(Problem): billing.verify_purchase(self.account, "disabled-unrelated", "test-purchase-token")
        play.assert_not_called()
