import uuid
from datetime import timedelta
from unittest.mock import patch

from django.contrib.auth.models import User
from django.core import signing
from django.core.exceptions import PermissionDenied
from django.db import transaction
from django.test import Client, TestCase, override_settings
from django.utils import timezone

from . import services
from .authentication import issue_token
from .models import Account, CashEntry, Ledger, OwnerAudit, OwnerCreditGrant, Purchase, Wallet
from .owner_credits import REVIEW_SALT, apply_grant


@override_settings(OWNER_USERNAME="owner", PROXY_MODE="none")
class OwnerCreditTests(TestCase):
    def setUp(self):
        self.owner = User.objects.create_superuser("owner", password="owner-test-password")
        self.account = Account.objects.create(google_sub="credit-target", email="creator@example.test", name="Creator")
        self.other = Account.objects.create(google_sub="credit-other", email="other@example.test", name="Other")
        self.wallet = Wallet.objects.create(account=self.account, balance_paise=1700, held_paise=100,
            monthly_balance_paise=500, monthly_held_paise=50, monthly_period_key="current-period",
            monthly_expires_at=timezone.now() + timedelta(days=10), topup_balance_paise=1000, topup_held_paise=25,
            credit_buckets_version=1)
        Wallet.objects.create(account=self.other, balance_paise=200, credit_buckets_version=1)
        self.client.force_login(self.owner)

    def review(self, **overrides):
        data = dict(action="review", account=self.account.pk, credits=500, note="Customer support bonus", grant_id=uuid.uuid4())
        data.update(overrides)
        return self.client.post("/owner/credits/", data)

    def confirm(self, response):
        return self.client.post("/owner/credits/", {"action": "confirm", "review_token": response.context["review_token"]})

    def test_only_configured_owner_can_view_or_grant(self):
        anonymous = Client()
        self.assertEqual(anonymous.get("/owner/credits/", HTTP_AUTHORIZATION="Bearer " + issue_token(self.account)).status_code, 302)
        self.assertEqual(anonymous.post("/owner/credits/", {"action": "review"}).status_code, 302)
        for user in (User.objects.create_user("staff", is_staff=True), User.objects.create_superuser("different-owner", password="different-password")):
            self.client.force_login(user)
            self.assertEqual(self.client.get("/owner/credits/").status_code, 403)
            self.assertEqual(self.client.post("/owner/credits/", {"action": "review"}).status_code, 403)
            with self.assertRaises(PermissionDenied):
                apply_grant(user, {})
        self.assertFalse(OwnerCreditGrant.objects.exists())

    def test_review_is_read_only_and_confirm_adds_audited_bonus_once(self):
        review = self.review()
        self.assertEqual(review.status_code, 200)
        self.assertContains(review, "creator@example.test")
        self.assertContains(review, "Confirm and add 500 credits")
        self.assertFalse(OwnerCreditGrant.objects.exists())
        self.assertFalse(Ledger.objects.exists())
        result = self.client.post("/owner/credits/", {"action": "confirm", "review_token": review.context["review_token"],
            "account": self.other.pk, "credits": 1_000_000, "note": "Unreviewed changed details"})
        self.assertEqual(result.status_code, 302)
        duplicate = self.confirm(review)
        self.assertEqual(duplicate.url, result.url)
        self.wallet.refresh_from_db()
        self.assertEqual((self.wallet.balance_paise, self.wallet.held_paise), (2200, 100))
        self.assertEqual((self.wallet.monthly_balance_paise, self.wallet.topup_balance_paise), (500, 1000))
        self.assertEqual(self.wallet.free_available_paise, 675)
        self.assertEqual(Wallet.objects.get(account=self.other).balance_paise, 200)
        self.assertEqual(OwnerCreditGrant.objects.count(), 1)
        grant = OwnerCreditGrant.objects.get()
        self.assertEqual((grant.credits, grant.note, grant.recorded_by_id), (500, "Customer support bonus", self.owner.pk))
        self.assertEqual((grant.ledger.kind, grant.ledger.balance_delta, grant.ledger.held_delta), ("owner_credit", 500, 0))
        self.assertEqual(OwnerAudit.objects.filter(action="grant_credits").count(), 1)
        self.assertFalse(CashEntry.objects.exists())
        self.assertFalse(Purchase.objects.exists())
        self.assertContains(self.client.get(result.url), "Credits added")
        self.assertContains(self.client.get("/owner/records/credits/"), "Customer support bonus")
        self.assertContains(self.client.get("/owner/records/users/"), f"/owner/credits/?user={self.account.pk}")
        self.assertIn("no-store", review["Cache-Control"])
        self.assertIn("no-store", self.client.get(result.url)["Cache-Control"])
        self.assertEqual(self.client.post(result.url).status_code, 405)

    def test_grant_never_expires_with_monthly_credits(self):
        self.confirm(self.review())
        self.wallet.refresh_from_db()
        with transaction.atomic():
            wallet = services.wallet_for_update(self.account)
            services.expire_monthly(wallet, force=True)
        self.wallet.refresh_from_db()
        self.assertEqual(self.wallet.free_available_paise, 675)
        self.assertEqual(self.wallet.topup_balance_paise, 1000)

    def test_invalid_amount_user_and_reason_cannot_change_wallet(self):
        for credits in (0, -1, "1.5", "invalid", 1_000_001):
            with self.subTest(credits=credits):
                response = self.review(credits=credits)
                self.assertTrue(response.context["form"].errors)
        for fields in ({"account": "missing-user"}, {"note": "tiny"}, {"note": "x" * 501}):
            self.assertTrue(self.review(**fields).context["form"].errors)
        self.wallet.refresh_from_db()
        self.assertEqual(self.wallet.balance_paise, 1700)
        self.assertFalse(Ledger.objects.exists())
        self.assertFalse(OwnerCreditGrant.objects.exists())

    def test_csrf_signature_expiry_and_unreviewed_confirm_are_rejected(self):
        strict = Client(enforce_csrf_checks=True)
        strict.force_login(self.owner)
        self.assertEqual(strict.post("/owner/credits/", {"action": "review"}).status_code, 403)
        self.assertEqual(strict.post("/owner/credits/", {"action": "confirm"}).status_code, 403)
        review = self.review()
        token = review.context["review_token"]
        bad = self.client.post("/owner/credits/", {"action": "confirm", "review_token": token + "changed"})
        self.assertContains(bad, "expired or was changed")
        payload = signing.loads(token, salt=REVIEW_SALT)
        with patch("django.core.signing.time.time", return_value=timezone.now().timestamp() - 601):
            expired = signing.dumps(payload, salt=REVIEW_SALT)
        self.assertContains(self.client.post("/owner/credits/", {"action": "confirm", "review_token": expired}), "expired or was changed")
        self.assertContains(self.client.post("/owner/credits/", {"action": "confirm", "account": self.account.pk, "credits": 500}), "expired or was changed")
        self.assertFalse(OwnerCreditGrant.objects.exists())
        self.assertFalse(Ledger.objects.exists())

    def test_same_reference_cannot_grant_again_to_another_user(self):
        identifier = uuid.uuid4()
        self.confirm(self.review(grant_id=identifier))
        conflicting = self.review(account=self.other.pk, grant_id=identifier)
        self.assertContains(self.confirm(conflicting), "already used for different details")
        self.assertEqual(Wallet.objects.get(account=self.other).balance_paise, 200)
        self.assertEqual(OwnerCreditGrant.objects.count(), 1)
        self.assertEqual(Ledger.objects.count(), 1)

    def test_account_disabled_after_review_cannot_receive_grant(self):
        review = self.review()
        Account.objects.filter(pk=self.account.pk).update(is_active=False)
        self.assertContains(self.confirm(review), "account is inactive")
        self.assertFalse(OwnerCreditGrant.objects.exists())
        self.assertFalse(Ledger.objects.exists())

    def test_audit_failure_rolls_back_wallet_ledger_and_grant(self):
        data = dict(account=str(self.account.pk), grant_id=uuid.uuid4(), credits=500, note="Customer support bonus")
        with patch("studio.owner_credits.OwnerAudit.objects.create", side_effect=RuntimeError("audit unavailable")):
            with self.assertRaises(RuntimeError):
                apply_grant(self.owner, data)
        self.wallet.refresh_from_db()
        self.assertEqual(self.wallet.balance_paise, 1700)
        self.assertFalse(OwnerCreditGrant.objects.exists())
        self.assertFalse(Ledger.objects.exists())

    def test_history_filters_by_user_and_grants_can_create_missing_wallet(self):
        self.confirm(self.review())
        Wallet.objects.filter(account=self.other).delete()
        result = self.confirm(self.review(account=self.other.pk, credits=75, note="One-time welcome help"))
        self.assertEqual(result.status_code, 302)
        self.assertEqual(Wallet.objects.get(account=self.other).free_available_paise, 75)
        response = self.client.get("/owner/records/credits/", {"user": self.account.pk})
        self.assertContains(response, "Customer support bonus")
        self.assertNotContains(response, "One-time welcome help")
