import uuid
from datetime import timedelta
from unittest.mock import patch

from django.contrib.auth import get_user_model
from django.core.cache import cache
from django.db import transaction
from django.test import Client, TestCase, override_settings
from django.utils import timezone
from rest_framework.test import APIClient

from . import authentication, services, web_payments
from .models import Account, Wallet, Ledger, Rate, Quote, Budget, Job, WebGeneration, WebFeedback, WebUserProfile, WebUserDay, WebCreditPack
from .web_testing import allowance


@override_settings(AUTH_ENABLED=True, GOOGLE_CLIENT_ID="web-client.apps.googleusercontent.com", WEB_ENABLED=True,
    WEB_TRIAL_MODE=True, WEB_WELCOME_CREDITS=100, WEB_TEST_GATE_ENABLED=False, WELCOME_CREDITS=200,
    PAYU_ENABLED=True, PAYU_MODE="live", PAYU_ALLOW_LIVE=True, PAYU_KEY="merchant", PAYU_SALT="server-salt",
    WEB_PUBLIC_URL="https://testserver", OWNER_USERNAME="owner")
class WebTrialTests(TestCase):
    def setUp(self):
        cache.clear()
        self.client=Client(enforce_csrf_checks=True)
        self.rate=Rate.objects.create(slug="trial-image", title="Test image", kind="image", provider_model="fal:test", unit_cost_paise=1)

    def login(self, sub="new-web-user"):
        config=self.client.get('/app/session/').json()
        with patch('studio.authentication.verify_google', return_value={"sub":sub, "email":sub+"@example.test", "nonce":config['nonce']}):
            result=self.client.post('/app/login/', {'credential':'verified-token-for-testing'}, content_type='application/json', HTTP_X_CSRFTOKEN=config['csrf'])
        self.assertEqual(result.status_code,200,result.content)
        self.account=Account.objects.get(google_sub=sub)
        self.headers={"HTTP_X_CSRFTOKEN":result.json()['csrf'],"HTTP_X_MOTION_ACCOUNT":str(self.account.pk)}
        return result

    def quote(self, amount, account=None):
        return Quote.objects.create(account=account or self.account, rate=self.rate, payload={"kind":"image", "prompt":"Test image", "revision":1},
            payload_hash="a"*64, provider_cost_paise=1, overhead_paise=0, markup_bps=0, fee_bps=0, charge_paise=amount,
            expires_at=timezone.now()+timedelta(minutes=5))

    def job(self, amount=30, state="succeeded", account=None, testing=True):
        account=account or self.account
        quote=self.quote(amount,account)
        budget,_=Budget.objects.get_or_create(day=timezone.now().date(),shard=0)
        job=Job.objects.create(account=account,quote=quote,budget=budget,idempotency_key=uuid.uuid4().hex,
            state=state, charged_paise=amount if state=="succeeded" else None,next_attempt_at=timezone.now())
        WebGeneration.objects.create(job=job,testing=testing)
        return job

    def post(self, path, data):
        return self.client.post(path,data,content_type='application/json',**self.headers)

    def test_new_web_welcome_is_100_once_and_android_does_not_top_it_up(self):
        self.login();self.login()
        self.assertEqual(Wallet.objects.get(account=self.account).balance_paise,100)
        self.assertEqual(Ledger.objects.filter(account=self.account,kind='welcome').count(),1)
        native=APIClient();native.credentials(HTTP_AUTHORIZATION='Bearer '+authentication.issue_token(self.account))
        self.assertEqual(native.get('/api/v1/wallet').json()['available_credits'],100)
        self.assertEqual(WebUserProfile.objects.count(),1)
        self.assertTrue(WebUserProfile.objects.get().registered_on_web)
        self.assertEqual(WebUserDay.objects.get().logins,2)
        self.client.get('/app/session/')
        self.assertEqual(WebUserDay.objects.get().visits,2)

    def test_existing_android_balance_is_preserved_but_web_allowance_is_capped(self):
        account=Account.objects.create(google_sub='existing-android',email='old@example.test')
        Wallet.objects.create(account=account)
        with transaction.atomic():services.grant_welcome_credits(account)
        self.login('existing-android')
        self.assertEqual(Wallet.objects.get(account=account).balance_paise,200)
        self.assertEqual(self.client.get('/api/v1/wallet',**self.headers).json()['available_credits'],100)
        self.assertFalse(WebUserProfile.objects.get().registered_on_web)
        native=APIClient();native.credentials(HTTP_AUTHORIZATION='Bearer '+authentication.issue_token(account))
        self.assertEqual(native.get('/api/v1/wallet').json()['available_credits'],200)

    def test_web_trial_reservations_cannot_exceed_limit_and_failures_release_it(self):
        self.login();first=self.job(60,'running')
        self.assertEqual(allowance(self.account)['available'],40)
        quote=self.quote(41)
        with patch('studio.services.submit_job') as submit:
            result=self.client.post('/api/v1/jobs',{'quote_id':str(quote.pk)},content_type='application/json',HTTP_IDEMPOTENCY_KEY='trial-second-job',**self.headers)
            self.assertEqual(result.status_code,402,result.content);submit.assert_not_called()
        first.state='failed';first.save(update_fields=['state'])
        self.assertEqual(allowance(self.account)['available'],100)
        first.state='succeeded';first.charged_paise=50;first.save(update_fields=['state','charged_paise'])
        self.assertEqual(allowance(self.account)['available'],50)
        self.job(500,testing=False)
        self.assertEqual(allowance(self.account)['available'],50)

    def test_idempotent_submission_can_recover_after_allowance_is_reserved(self):
        self.login();job=self.job(100,'running')
        with patch('studio.services.submit_job',return_value=(job,False)) as submit:
            result=self.client.post('/api/v1/jobs',{'quote_id':str(job.quote_id)},content_type='application/json',HTTP_IDEMPOTENCY_KEY=job.idempotency_key,**self.headers)
        self.assertEqual(result.status_code,200,result.content);submit.assert_called_once()
        self.assertEqual(WebGeneration.objects.count(),1)

    def test_all_new_web_sales_and_checkout_are_blocked_without_disabling_reconciliation(self):
        self.login()
        pack=WebCreditPack.objects.create(slug='trial-pack',title='Pack',credits=2500,amount_paise=149900,enabled=True)
        result=self.post('/app/payments/',{'pack':pack.pk,'phone':'9876543210','request_key':'trial-purchase-request'})
        self.assertEqual(result.status_code,403,result.content)
        self.assertFalse(self.client.get('/app/packs/',**self.headers).json()['enabled'])
        self.assertContains(self.client.get('/app/pricing/'),'Purchases paused',count=4)
        web_payments.require_config()  # Existing signed callbacks can still reconcile.

    def test_feedback_is_owned_successful_idempotent_and_validated(self):
        self.login();job=self.job()
        data={"context":"generation","job_id":str(job.pk),"outcome":"submitted","rating":"like","interest":"yes","comment":"Great result"}
        self.assertEqual(self.post('/app/feedback/',data).status_code,200)
        self.assertEqual(self.post('/app/feedback/',data).status_code,200)
        self.assertEqual(WebFeedback.objects.count(),1)
        self.assertEqual(self.client.get('/app/feedback/',**self.headers).json()['pending'],[])
        other=Account.objects.create(google_sub='other',email='other@example.test');Wallet.objects.create(account=other)
        foreign=self.job(account=other)
        self.assertEqual(self.post('/app/feedback/',dict(data,job_id=str(foreign.pk))).status_code,404)
        running=self.job(state='running')
        self.assertEqual(self.post('/app/feedback/',dict(data,job_id=str(running.pk))).status_code,404)
        self.assertEqual(self.post('/app/feedback/',dict(data,interest='bad')).status_code,400)
        self.assertEqual(self.post('/app/feedback/',dict(data,comment='x'*501)).status_code,400)
        self.assertEqual(self.client.post('/app/feedback/',data,content_type='application/json',HTTP_X_MOTION_ACCOUNT=str(self.account.pk)).status_code,403)

    def test_completed_jobs_survive_browser_restart_and_exhaustion_is_not_a_pending_hold(self):
        self.login();job=self.job(100,'running')
        self.assertFalse(self.client.get('/app/feedback/',**self.headers).json()['exhausted'])
        job.state='succeeded';job.charged_paise=100;job.save(update_fields=['state','charged_paise'])
        result=self.client.get('/app/feedback/',**self.headers).json()
        self.assertEqual(result['pending'][0]['id'],str(job.pk));self.assertTrue(result['exhausted'])
        self.assertEqual(self.post('/app/feedback/',{"context":"generation","job_id":str(job.pk),"outcome":"dismissed"}).status_code,200)
        self.assertEqual(self.post('/app/feedback/',{"context":"credits","outcome":"submitted","interest":"maybe"}).status_code,200)
        self.assertTrue(self.client.get('/app/feedback/',**self.headers).json()['credits_answered'])
        self.assertEqual(Wallet.objects.get(account=self.account).balance_paise,100) # Feedback never edits wallets.

    def test_owner_only_analytics_and_feedback_are_visible(self):
        self.login();self.job()
        self.assertEqual(self.client.get('/owner/visitors/').status_code,302)
        owner=get_user_model().objects.create_superuser(username='owner',password='owner-testing-password')
        self.client.force_login(owner)
        response=self.client.get('/owner/visitors/')
        self.assertContains(response,'New accounts registered on web')
        self.assertContains(response,self.account.email)
        self.assertEqual(response.context['activity']['new_accounts'],1)
        self.assertEqual(response.context['activity']['generations'],1)

    def test_traffic_metadata_is_categorical_and_first_landing_is_preserved(self):
        from .models import WebVisitorDay
        self.client.get('/app/?utm_source=instagram',HTTP_USER_AGENT='Android mobile',HTTP_REFERER='https://instagram.com/some-private-path?token=secret')
        self.client.get('/app/pricing/',HTTP_USER_AGENT='Android mobile')
        row=WebVisitorDay.objects.get()
        self.assertEqual((row.views,row.source,row.device,row.landing,row.new_browser),(2,'instagram','mobile','/app/',True))
        self.assertNotIn('secret',row.visitor_hash)

    def test_first_visit_welcome_is_anonymous_csrf_protected_and_recorded_once(self):
        from .models import WebVisitorInterest
        self.client.get('/app/')
        config=self.client.get('/app/session/').json()
        self.assertTrue(config['visitor_prompt'])
        data={"outcome":"submitted","interest":"yes","goal":"Story videos for YouTube"}
        self.assertEqual(self.client.post('/app/visitor-interest/',data,content_type='application/json').status_code,403)
        headers={"HTTP_X_CSRFTOKEN":config['csrf']}
        self.assertEqual(self.client.post('/app/visitor-interest/',data,content_type='application/json',**headers).status_code,200)
        self.assertEqual(self.client.post('/app/visitor-interest/',data,content_type='application/json',**headers).status_code,200)
        self.assertEqual(WebVisitorInterest.objects.count(),1)
        self.assertEqual(WebVisitorInterest.objects.get().goal,data['goal'])
        self.assertFalse(self.client.get('/app/session/').json()['visitor_prompt'])
        self.assertFalse(Account.objects.exists())
        self.assertEqual(self.client.post('/app/visitor-interest/',dict(data,goal='x'*301),content_type='application/json',**headers).status_code,400)
