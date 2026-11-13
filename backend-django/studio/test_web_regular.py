import io
from unittest.mock import patch

from django.core.cache import cache
from django.core.management import call_command
from django.db import transaction
from django.test import Client, TestCase, override_settings
from rest_framework.test import APIClient

from . import authentication, services, web_payments
from .models import Account, Wallet, Ledger, WebPayment, CashEntry, WebCreditPack


@override_settings(AUTH_ENABLED=True, GOOGLE_CLIENT_ID="web-client.apps.googleusercontent.com", WEB_ENABLED=True,
    WELCOME_CREDITS=300, WEB_TRIAL_MODE=True, WEB_TEST_GATE_ENABLED=False, PAYU_ENABLED=True, PAYU_MODE="test",
    PAYU_KEY="test-merchant", PAYU_SALT="test-server-only-salt", PAYU_ALLOW_LIVE=False,
    WEB_PUBLIC_URL="https://testserver", WEB_PACK_PRICES={2500:0,5000:0,10000:0,20000:0})
class RegularWebTests(TestCase):
    """Even a stale trial setting cannot restore the removed trial flow."""
    def setUp(self):
        cache.clear()
        self.browser=Client(enforce_csrf_checks=True)
        self.account=Account.objects.create(google_sub="regular-web-user",email="creator@example.test")
        Wallet.objects.create(account=self.account)

    def login(self):
        config=self.browser.get('/app/session/').json()
        with patch('studio.authentication.verify_google',return_value=dict(sub=self.account.google_sub,email=self.account.email,nonce=config['nonce'])):
            response=self.browser.post('/app/login/',{'credential':'google-token-for-local-test'},content_type='application/json',HTTP_X_CSRFTOKEN=config['csrf'])
        self.assertEqual(response.status_code,200,response.content)
        self.headers={'HTTP_X_CSRFTOKEN':response.json()['csrf'],'HTTP_X_MOTION_ACCOUNT':str(self.account.pk)}

    def sandbox(self):
        call_command('sync_web_packs',stdout=io.StringIO())
        return web_payments.create_order(self.account,dict(pack='web-sandbox-test',phone='9876543210',request_key='sandbox-checkout-request'))

    def test_google_login_grants_300_once_with_the_same_native_wallet(self):
        self.login();self.login()
        self.assertEqual(Wallet.objects.get(account=self.account).balance_paise,300)
        self.assertEqual(Ledger.objects.filter(account=self.account,kind='welcome').count(),1)
        native=APIClient();native.credentials(HTTP_AUTHORIZATION='Bearer '+authentication.issue_token(self.account))
        self.assertEqual(native.get('/api/v1/wallet').json()['available_credits'],300)
        self.assertEqual(self.browser.get('/api/v1/wallet',**self.headers).json()['available_credits'],300)
        config=self.browser.get('/app/session/').json()
        self.assertFalse(config['testing']['enabled']);self.assertFalse(config['visitor_prompt'])

    def test_existing_shared_balance_is_not_capped_or_reset(self):
        self.login()
        with transaction.atomic():
            wallet=services.wallet_for_update(self.account)
            services.entry(wallet,'purchase','regular-test-topup',balance_delta=5000,topup_balance_delta=5000)
        result=self.browser.get('/api/v1/wallet',**self.headers).json()
        self.assertEqual(result['available_credits'],5300)
        self.assertIsNone(result['web_testing'])

    def test_existing_welcome_grants_are_not_replaced_or_repeated(self):
        with transaction.atomic():services.grant_welcome_credits(self.account,100)
        self.login()
        self.assertEqual(self.browser.get('/api/v1/wallet',**self.headers).json()['available_credits'],100)
        self.assertEqual(Ledger.objects.filter(account=self.account,kind='welcome').count(),1)

    def test_no_surveys_or_trial_controls_are_rendered_and_old_writers_are_retired(self):
        self.login()
        response=self.browser.get('/app/')
        self.assertContains(response,'300 one-time welcome credits')
        self.assertNotContains(response,'id="visitor-welcome"');self.assertNotContains(response,'id="feedback-dialog"')
        self.assertNotContains(response,'id="trial-credit-info"')
        self.assertEqual(self.browser.post('/app/feedback/',{},content_type='application/json',**self.headers).status_code,410)
        self.assertEqual(self.browser.get('/app/feedback/',**self.headers).json()['pending'],[])

    def test_sandbox_payment_is_one_rupee_and_does_not_require_regular_prices(self):
        self.login();order=self.sandbox()
        self.assertEqual((order.mode,order.amount_paise,order.credits),('test',100,1))
        self.assertEqual(self.sandbox().pk,order.pk)
        catalog=self.browser.get('/app/packs/',**self.headers).json()
        self.assertEqual(catalog['packs'],[])
        self.assertEqual(catalog['sandbox_pack']['id'],'web-sandbox-test')
        response=self.browser.get(f'/app/payments/{order.pk}/checkout/')
        self.assertContains(response,'https://test.payu.in/_payment')
        self.assertContains(response,'value="1.00"');self.assertNotContains(response,'test-server-only-salt')

    def test_verified_sandbox_success_never_grants_live_credits_or_cash(self):
        self.login();order=self.sandbox()
        payment=dict(txnid=order.pk.hex,status='success',unmappedstatus='captured',mihpayid='123456789',amt='1.00',
            udf1=str(self.account.pk),udf2=order.pack_id,udf3='test',productinfo='MotionMagic AI sandbox test',email=order.email)
        with patch('studio.web_payments.api',side_effect=[{'transaction_details':{order.pk.hex:payment}},{'status':1,'msg':'No Refunds Found for the transaction.'}]):
            self.assertEqual(web_payments.reconcile(order).state,'paid')
        self.assertEqual(Wallet.objects.get(account=self.account).balance_paise,300)
        self.assertFalse(CashEntry.objects.exists())

    def test_sandbox_product_cannot_be_used_in_live_mode_even_if_database_row_is_enabled(self):
        self.login();self.sandbox()
        with override_settings(PAYU_MODE='live',PAYU_ALLOW_LIVE=True):
            from .errors import Problem
            with self.assertRaises(Problem):
                web_payments.create_order(self.account,dict(pack='web-sandbox-test',phone='9876543210',request_key='live-sandbox-attempt'))
            self.assertIsNone(self.browser.get('/app/packs/',**self.headers).json()['sandbox_pack'])
            call_command('sync_web_packs',stdout=io.StringIO())
            self.assertFalse(WebCreditPack.objects.get(pk='web-sandbox-test').enabled)
        self.assertEqual(WebPayment.objects.count(),1)
