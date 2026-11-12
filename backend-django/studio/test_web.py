import hashlib
import io
import uuid
from datetime import timedelta
from unittest.mock import patch
from urllib.parse import urlencode

from django.conf import settings
from django.core.cache import cache
from django.core.management import call_command
from django.test import Client, TestCase, override_settings
from django.utils import timezone
from rest_framework.test import APIClient

from . import authentication, web_payments
from .errors import Problem
from .models import Account, CashEntry, Ledger, Product, Wallet, WebCreditPack, WebPayment, WebSession


@override_settings(AUTH_ENABLED=True, GOOGLE_CLIENT_ID="web-client.apps.googleusercontent.com", WEB_ENABLED=True,
    PAYU_ENABLED=True, PAYU_MODE="live", PAYU_ALLOW_LIVE=True, PAYU_KEY="merchant-test-key", PAYU_SALT="server-only-test-salt",
    WEB_PUBLIC_URL="https://api.example.test", WELCOME_CREDITS=200, OWNER_USERNAME="motion-owner", WEB_TRIAL_MODE=False)
class WebTests(TestCase):
    def setUp(self):
        cache.clear()
        self.browser = Client(enforce_csrf_checks=True)
        self.account = Account.objects.create(google_sub="same-google-account", email="creator@example.test", name="Creator")
        Wallet.objects.create(account=self.account, balance_paise=200, credit_buckets_version=1)
        Ledger.objects.create(account=self.account, kind="welcome", reference=f"welcome:{self.account.pk}", balance_delta=200, balance_after=200, held_after=0)
        self.pack = WebCreditPack.objects.create(slug="web-2500", title="Creator Pack", credits=2500, amount_paise=149900, enabled=True)

    def login(self, browser=None, account=None):
        browser = browser or self.browser
        account = account or self.account
        config = browser.get('/app/session/').json()
        profile = dict(sub=account.google_sub, email=account.email, name=account.name, nonce=config['nonce'])
        with patch('studio.authentication.verify_google', return_value=profile):
            result = browser.post('/app/login/', {'credential':'google-token-for-local-test'}, content_type='application/json', HTTP_X_CSRFTOKEN=config['csrf'])
        self.assertEqual(result.status_code, 200, result.content)
        self.csrf = result.json()['csrf']
        self.headers = dict(HTTP_X_CSRFTOKEN=self.csrf, HTTP_X_MOTION_ACCOUNT=str(account.pk))
        return result

    def order(self, key='web-purchase-request-1'):
        return web_payments.create_order(self.account, dict(pack=self.pack.pk, phone='9876543210', request_key=key))

    def payment(self, order, **changes):
        return dict(txnid=order.pk.hex, status='success', unmappedstatus='captured', mihpayid='123456789', amt='1499.00',
            udf1=str(order.account_id), udf2=order.pack_id, udf3=order.mode,
            productinfo=f'MotionMagic AI {order.credits} credits', email=order.email, **changes)

    def verified_api(self, order, refund='0.00', **changes):
        payment=self.payment(order)
        payment.update(changes)
        refunds={'status':1,'msg':'No Refunds Found for the transaction.'} if refund=='0.00' else {
            'status':1, 'Refund Details':{'123456789':[dict(PayuID='123456789', RequestID='r1', Status='success', Amount=refund)]}}
        return patch('studio.web_payments.api', side_effect=[{'transaction_details':{order.pk.hex:payment}}, refunds])

    def due(self, order):
        WebPayment.objects.filter(pk=order.pk).update(check_after=timezone.now()-timedelta(seconds=1))
        order.refresh_from_db()

    def test_browser_and_android_use_same_google_identity_and_welcome_once(self):
        self.login()
        native=APIClient()
        with patch('studio.authentication.verify_google', return_value=dict(sub=self.account.google_sub,email=self.account.email,name='Creator')):
            result=native.post('/auth/motion-ai/google/native',dict(app='motionmagic',idToken='native-google-token-for-test'),format='json')
        self.assertEqual(result.status_code,200)
        self.assertEqual(result.json()['user']['id'],str(self.account.pk))
        self.assertEqual(Account.objects.filter(google_sub=self.account.google_sub).count(),1)
        self.assertEqual(Ledger.objects.filter(account=self.account,kind='welcome').count(),1)
        self.assertEqual(self.browser.get('/api/v1/wallet',**self.headers).json()['available_credits'],200)

    def test_web_sessions_are_http_only_absolute_and_independent_of_native_logout(self):
        result=self.login()
        cookie=result.cookies[settings.WEB_SESSION_COOKIE]
        self.assertTrue(cookie['httponly']);self.assertEqual(cookie['samesite'],'Lax')
        saved=WebSession.objects.get();self.assertLessEqual(saved.expires_at,timezone.now()+timedelta(days=90))
        token=authentication.issue_token(self.account)
        response=self.browser.post('/app/logout/',{},content_type='application/json',**self.headers)
        self.assertEqual(response.status_code,200)
        native=APIClient();native.credentials(HTTP_AUTHORIZATION='Bearer '+token)
        self.assertEqual(native.get('/auth/motion-ai/me').status_code,200)
        self.assertFalse(WebSession.objects.exists())

    def test_expired_cookie_and_cross_tab_account_binding_are_rejected(self):
        self.login()
        self.assertEqual(self.browser.get('/api/v1/wallet',HTTP_X_MOTION_ACCOUNT='another-user').status_code,401)
        WebSession.objects.update(expires_at=timezone.now()-timedelta(seconds=1))
        self.assertEqual(self.browser.get('/api/v1/wallet',**self.headers).status_code,401)

    def test_google_nonce_and_csrf_are_required(self):
        config=self.browser.get('/app/session/').json()
        self.assertEqual(self.browser.post('/app/login/',{'credential':'long-google-token-for-test'},content_type='application/json').status_code,403)
        with patch('studio.authentication.verify_google', return_value=dict(sub='other',email='other@example.test',nonce='wrong')):
            self.assertEqual(self.browser.post('/app/login/',{'credential':'long-google-token-for-test'},content_type='application/json',HTTP_X_CSRFTOKEN=config['csrf']).status_code,401)
        self.assertFalse(WebSession.objects.exists())

    def test_browser_mutations_need_csrf_but_native_bearer_does_not(self):
        self.login()
        self.assertEqual(self.browser.post('/api/v1/quotes',{},content_type='application/json',HTTP_X_MOTION_ACCOUNT=str(self.account.pk)).status_code,403)
        native=APIClient(enforce_csrf_checks=True);native.credentials(HTTP_AUTHORIZATION='Bearer '+authentication.issue_token(self.account))
        result=native.post('/api/v1/quotes',{},format='json')
        self.assertIn(result.status_code,(400,409)) # business validation, not CSRF failure
        self.assertEqual(self.browser.post('/api/v1/billing/verify',{},content_type='application/json',**self.headers).status_code,401)

    def test_pack_sync_and_catalog_never_change_android_products(self):
        play=Product.objects.get(pk='motion_pack_2500');before=(play.credit_paise,play.enabled)
        with override_settings(WEB_PACK_PRICES={2500:149900,5000:299900,10000:599900,20000:1199900}):
            call_command('sync_web_packs',stdout=io.StringIO())
        play.refresh_from_db();self.assertEqual((play.credit_paise,play.enabled),before)
        self.login();catalog=self.browser.get('/app/packs/',**self.headers).json()
        self.assertEqual(len(catalog['packs']),4)
        self.assertEqual(catalog['packs'][0]['amount_paise'],149900)

    def test_order_price_credits_and_account_are_server_owned_and_idempotent(self):
        first=self.order();again=self.order();self.assertEqual(first.pk,again.pk)
        self.assertEqual((first.credits,first.amount_paise,str(first.account_id)),(2500,149900,str(self.account.pk)))
        self.pack.amount_paise=999900;self.pack.save()
        self.assertEqual(self.order().amount_paise,149900)
        for extra in ('credits','amount_paise','account'):
            with self.assertRaises(Problem):web_payments.create_order(self.account,dict(pack=self.pack.pk,phone='9876543210',request_key='request-12345',**{extra:1}))

    def test_order_and_checkout_are_user_scoped(self):
        order=self.order();other=Account.objects.create(google_sub='other',email='other@example.test',name='Other')
        self.login(account=other)
        self.assertEqual(self.browser.post(f'/app/payments/{order.pk}/verify/',{},content_type='application/json',**self.headers).status_code,404)
        self.assertEqual(self.browser.get(f'/app/payments/{order.pk}/checkout/').status_code,404)

    def test_verified_payment_grants_shared_wallet_once_and_records_receipt(self):
        order=self.order()
        with self.verified_api(order):web_payments.reconcile(order)
        self.due(order)
        with self.verified_api(order):web_payments.reconcile(order)
        wallet=Wallet.objects.get(account=self.account)
        self.assertEqual((wallet.balance_paise,wallet.topup_balance_paise),(2700,2500))
        self.assertEqual(CashEntry.objects.filter(kind='receipt',account=self.account).count(),1)
        self.assertEqual(Ledger.objects.filter(reference=f'payu:{order.pk}').count(),1)
        native=APIClient();native.force_authenticate(self.account)
        self.assertEqual(native.get('/api/v1/wallet').json()['topup_credits'],2500)

    def test_pending_and_forged_verified_details_never_grant_credits(self):
        order=self.order()
        with patch('studio.web_payments.api',return_value={'transaction_details':{order.pk.hex:{'status':'pending'}}}):
            self.assertEqual(web_payments.reconcile(order).state,'pending')
        for changes in ({'amt':'1.00'},{'udf1':'other-account'},{'productinfo':'500 coins'},{'mihpayid':'bad'}):
            self.due(order)
            with self.verified_api(order,**changes),self.assertRaises(Problem):web_payments.reconcile(order)
        self.assertEqual(Wallet.objects.get(account=self.account).balance_paise,200)

    def test_timeout_retains_order_and_late_captured_payment_is_recoverable(self):
        order=self.order()
        with patch('studio.web_payments.api',side_effect=Problem('temporary',503)),self.assertRaises(Problem):web_payments.reconcile(order)
        WebPayment.objects.filter(pk=order.pk).update(expires_at=timezone.now()-timedelta(hours=1));self.due(order)
        with self.verified_api(order):self.assertEqual(web_payments.reconcile(order).state,'paid')
        self.assertEqual(Wallet.objects.get(account=self.account).balance_paise,2700)

    @override_settings(PAYU_MODE='test')
    def test_sandbox_payments_never_create_live_credits_or_cash(self):
        order=self.order()
        with self.verified_api(order):self.assertEqual(web_payments.reconcile(order).state,'paid')
        self.assertEqual(Wallet.objects.get(account=self.account).balance_paise,200)
        self.assertFalse(CashEntry.objects.exists())

    def test_partial_and_full_refunds_revoke_only_purchased_credits_once(self):
        order=self.order()
        with self.verified_api(order):web_payments.reconcile(order)
        self.due(order)
        with self.verified_api(order,refund='749.50'):web_payments.reconcile(order)
        self.assertEqual(Wallet.objects.get(account=self.account).balance_paise,1450)
        self.due(order)
        with self.verified_api(order,refund='1499.00'):web_payments.reconcile(order)
        self.due(order)
        with self.verified_api(order,refund='1499.00'):web_payments.reconcile(order)
        self.assertEqual(Wallet.objects.get(account=self.account).balance_paise,200)
        self.assertEqual(CashEntry.objects.filter(kind='refund').count(),2)

    def test_form_callback_requires_hash_and_independent_api_verification(self):
        order=self.order();fields=web_payments.checkout_fields(order);fields.update(status='success',mihpayid='123456789')
        fields['hash']=web_payments.response_hash(fields)
        self.assertEqual(self.browser.post('/app/payments/payu/return/',urlencode(dict(fields,hash='0'*128)),content_type='application/x-www-form-urlencoded').status_code,400)
        with self.verified_api(order):result=self.browser.post('/app/payments/payu/return/',urlencode(fields),content_type='application/x-www-form-urlencoded')
        self.assertEqual(result.status_code,303)
        self.assertEqual(Wallet.objects.get(account=self.account).balance_paise,2700)

    def test_page_csp_fonts_cache_and_no_owner_privileges(self):
        response=self.browser.get('/app/')
        self.assertEqual(response.status_code,200);self.assertContains(response,'Your ideas.')
        self.assertIn('frame-ancestors',response['Content-Security-Policy'])
        self.assertNotContains(response,settings.PAYU_SALT)
        self.assertNotContains(response,'src=""')
        self.assertEqual(self.browser.get('/app/fonts/font-regular.ttf').status_code,302)
        self.login();self.assertEqual(self.browser.get('/owner/').status_code,302)

    def test_owned_checkout_contains_only_public_payu_fields(self):
        order=self.order();self.login()
        result=self.browser.get(f'/app/payments/{order.pk}/checkout/')
        self.assertEqual(result.status_code,200)
        self.assertContains(result,'https://secure.payu.in/_payment')
        self.assertContains(result,'1499.00')
        self.assertNotContains(result,settings.PAYU_SALT)

    def test_live_payment_id_can_match_a_sandbox_id_without_mixing_wallets(self):
        with override_settings(PAYU_MODE='test'):
            sandbox=self.order('sandbox-request-1')
            with self.verified_api(sandbox):web_payments.reconcile(sandbox)
        live=self.order('live-request-1')
        with self.verified_api(live):web_payments.reconcile(live)
        self.assertEqual(Wallet.objects.get(account=self.account).balance_paise,2700)
        self.assertEqual(CashEntry.objects.filter(kind='receipt').count(),1)

    def test_an_already_refunded_payment_never_exposes_spendable_pack_credits(self):
        order=self.order()
        with self.verified_api(order,refund='1499.00'):
            self.assertEqual(web_payments.reconcile(order).state,'refunded')
        wallet=Wallet.objects.get(account=self.account)
        self.assertEqual((wallet.balance_paise,wallet.topup_balance_paise),(200,0))

    def test_a_payment_cannot_credit_two_orders(self):
        first=self.order()
        with self.verified_api(first):web_payments.reconcile(first)
        second=self.order('second-order-request')
        with self.verified_api(second),self.assertRaises(Problem):web_payments.reconcile(second)
        self.assertEqual(Wallet.objects.get(account=self.account).balance_paise,2700)
        self.assertEqual(CashEntry.objects.filter(kind='receipt').count(),1)

    def test_disabling_new_web_sales_does_not_strand_an_existing_paid_order(self):
        order=self.order()
        with override_settings(PAYU_ENABLED=False,WEB_ENABLED=False):
            with self.verified_api(order):self.assertEqual(web_payments.reconcile(order).state,'paid')
            with self.assertRaises(Problem):self.order('new-sales-disabled')
        self.assertEqual(Wallet.objects.get(account=self.account).balance_paise,2700)

    def test_public_pricing_displays_real_prices_before_google_login(self):
        response = self.browser.get('/app/pricing/')
        self.assertEqual(response.status_code, 200)
        self.assertContains(response, '1499.00')
        self.assertContains(response, '2,500')
        self.assertContains(response, '20,000')
        self.assertContains(response, 'Price not configured yet', count=3)
        self.assertContains(response, 'Try test checkout', count=0)
        self.assertContains(response, 'Choose pack')
        self.assertNotContains(response, self.account.email)
        self.assertIn('no-store', response['Cache-Control'])
        with override_settings(PAYU_ENABLED=False):
            response = self.browser.get('/app/pricing/')
            self.assertContains(response, 'Not available yet', count=4)
            self.assertNotContains(response, 'Choose pack')

    def test_public_pricing_does_not_show_zero_as_free_or_bypass_testing_gate(self):
        self.pack.amount_paise=0
        self.pack.save()
        response=self.browser.get('/app/pricing/')
        self.assertContains(response, 'Price not configured yet', count=4)
        self.assertNotContains(response, '1499.00')
        with override_settings(WEB_TEST_GATE_ENABLED=True, WEB_TEST_USERNAME='tester', WEB_TEST_PASSWORD='a-long-test-password'):
            self.assertEqual(self.browser.get('/app/pricing/').status_code, 401)
        with override_settings(WEB_ENABLED=False):
            self.assertEqual(self.browser.get('/app/pricing/').status_code, 404)
