from datetime import datetime, timedelta, timezone as dt_timezone
from decimal import Decimal
from unittest.mock import patch
import json

from django.contrib.auth.models import User
from django.core.cache import cache
from django.test import TestCase, Client, override_settings
from django.utils import timezone

from . import finance, fal_billing, services
from .authentication import issue_token
from .models import Account, Wallet, Rate, Quote, Budget, Job, CashEntry, ProviderCost


@override_settings(OWNER_USERNAME='owner', PROXY_MODE='none')
class OwnerTests(TestCase):
    def setUp(self):
        cache.clear()
        self.owner = User.objects.create_superuser('owner', password='owner-test-password')
        self.account = Account.objects.create(google_sub='one', email='one@example.test', name='One')
        Wallet.objects.create(account=self.account, balance_paise=10000)
        self.rate = Rate.objects.create(slug='qwen', title='Qwen', kind='image', provider_model='fal:qwen-image', unit_cost_paise=100, enabled=True)
        self.quote = Quote.objects.create(account=self.account, rate=self.rate, payload={'model':'fal:qwen-image'}, payload_hash='x'*64,
            provider_cost_paise=100, overhead_paise=0, markup_bps=15000, fee_bps=0, charge_paise=250, expires_at=timezone.now())
        self.job = Job.objects.create(account=self.account, quote=self.quote, budget=Budget.objects.create(day=timezone.now().date(), shard=0),
            idempotency_key='job', state='succeeded', next_attempt_at=timezone.now(), settled_at=timezone.now(),
            charged_paise=250, actual_cost_paise=100, provider_id='request-1', provider_context={'endpoint':'fal-ai/qwen-image'})

    def test_owner_routes_reject_app_tokens_staff_and_other_superusers(self):
        self.assertEqual(self.client.get('/owner/', HTTP_AUTHORIZATION='Bearer '+issue_token(self.account)).status_code, 302)
        for user in [User.objects.create_user('staff', is_staff=True), User.objects.create_superuser('other', password='different')]:
            self.client.force_login(user)
            for path in ['/owner/', '/owner/records/ledger/', '/owner/export/jobs/', '/owner/add/cash/']:
                self.assertEqual(self.client.get(path).status_code, 403)
        with override_settings(OWNER_USERNAME=''):
            self.assertEqual(self.client.get('/owner/login/').status_code,404)

    def test_all_pages_and_exports_render_without_secrets(self):
        self.client.force_login(self.owner)
        paths=['/owner/', '/owner/add/cash/', '/owner/add/cost/', f'/owner/jobs/{self.job.pk}/']
        paths += ['/owner/records/'+kind+'/' for kind in ['jobs','pending','users','ledger','cash','audit','purchases','rates']]
        for path in paths:
            response=self.client.get(path)
            self.assertEqual(response.status_code,200,path)
            self.assertIn('no-store',response['Cache-Control'])
        for kind in ['jobs','cash','ledger']:
            response=self.client.get('/owner/export/'+kind+'/')
            self.assertEqual(response.status_code,200)
            self.assertNotIn(b'token_encrypted',b''.join(response.streaming_content))
        self.assertEqual(self.client.get('/owner/logout/').status_code,405)

    def test_csrf_is_required_even_for_owner(self):
        client=Client(enforce_csrf_checks=True)
        self.assertEqual(client.post('/owner/login/', {'username':'owner','password':'owner-test-password'}).status_code,403)
        client.force_login(self.owner)
        self.assertEqual(client.post('/owner/add/cash/',{}).status_code,403)
        self.assertEqual(client.post('/owner/logout/',{}).status_code,403)

    def test_login_and_throttle(self):
        response=self.client.post('/owner/login/',{'username':'owner','password':'owner-test-password'})
        self.assertEqual(response.status_code,302)
        self.assertEqual(self.client.get('/owner/').status_code,200)
        self.client.post('/owner/logout/')
        cache.clear()
        for _ in range(10):
            self.assertEqual(self.client.post('/owner/login/',{'username':'owner','password':'wrong'}).status_code,200)
        self.assertEqual(self.client.post('/owner/login/',{'username':'owner','password':'wrong'}).status_code,429)

    def test_cash_exact_paise_duplicate_and_append_only_reversal(self):
        self.client.force_login(self.owner)
        data={'occurred_at':'2026-10-30T12:00','kind':'receipt','amount':'1.01','reference':'bank-1','note':'Bank statement','account':self.account.pk}
        self.assertEqual(self.client.post('/owner/add/cash/',data).status_code,302)
        self.assertEqual(self.client.post('/owner/add/cash/',data).status_code,200)
        item=CashEntry.objects.get()
        self.assertEqual(item.amount_paise,101)
        self.assertEqual(self.client.get('/owner/records/cash/').status_code,200)
        finance.reverse_cash(item.pk,self.owner,'Correction')
        self.assertEqual(CashEntry.objects.count(),2)
        with self.assertRaises(ValueError):finance.reverse_cash(item.pk,self.owner,'Again')
        totals,_,_=finance.summary(finance.period({'start':'2026-10-30','end':'2026-10-30'}))
        self.assertEqual(totals['receipt'],0)

    def test_failed_generation_cost_is_loss_and_fee_rounds_up(self):
        self.quote.fee_bps=1500
        self.quote.save()
        totals,_,_=finance.summary(finance.period({}))
        self.assertEqual(totals['estimated_fee'],38)
        Job.objects.filter(pk=self.job.pk).update(state='failed',charged_paise=0)
        totals,_,_=finance.summary(finance.period({}))
        self.assertEqual((totals['estimated_margin'],totals['failed']),(-100,1))

    def test_cost_revisions_do_not_double_count_or_change_wallet(self):
        filters=finance.period({})
        totals,_,_=finance.summary(filters)
        self.assertEqual((totals['estimated_margin'],totals['receipt'],totals['reconciled_margin']),(150,0,None))
        finance.record_cost(self.job.pk,101,'invoice-1',self.owner)
        finance.record_cost(self.job.pk,102,'invoice-2',self.owner)
        _,changed=finance.record_cost(self.job.pk,102,'invoice-2',self.owner)
        self.assertFalse(changed)
        _,changed=finance.record_cost(self.job.pk,999,'late API',source='fal_billing')
        self.assertFalse(changed)
        totals,_,_=finance.summary(filters)
        self.assertEqual((totals['billed_provider'],totals['reconciled_margin'],totals['jobs']),(102,148,1))
        self.assertEqual(ProviderCost.objects.count(),2)
        self.assertEqual(Wallet.objects.get().balance_paise,10000)

    def test_india_day_boundary_and_month_user_groups(self):
        Job.objects.filter(pk=self.job.pk).update(settled_at=datetime(2026,10,29,18,30,tzinfo=dt_timezone.utc))
        for group in ['day','month','user']:
            totals,rows,_=finance.summary(finance.period({'start':'2026-10-30','end':'2026-10-30','group':group}))
            self.assertEqual((totals['jobs'],len(rows)),(1,1))
        totals,_,_=finance.summary(finance.period({'start':'2026-10-29','end':'2026-10-29'}))
        self.assertEqual(totals['jobs'],0)

    @override_settings(FAL_BILLING_KEY='billing-test', USD_INR_RATE=Decimal('83.25'))
    @patch('studio.fal_billing.fetch_events')
    def test_billing_rounds_once_missing_is_unknown_and_repeat_is_idempotent(self,fetch):
        fetch.return_value=[]
        self.assertEqual(fal_billing.sync_recent()['awaiting_billing'],1)
        self.assertFalse(ProviderCost.objects.exists())
        Job.objects.update(cost_checked_at=None)
        fetch.return_value=[{'request_id':'request-1','endpoint':'fal-ai/qwen-image','timestamp':timezone.now().isoformat(),'unit':'image','cost':'0.045'}]
        self.assertEqual(fal_billing.sync_recent()['revisions'],1)
        self.assertEqual(ProviderCost.objects.get().amount_paise,375)
        Job.objects.update(cost_checked_at=None)
        self.assertEqual(fal_billing.sync_recent()['revisions'],0)

    @override_settings(FAL_BILLING_KEY='billing-test')
    @patch('studio.fal_billing.requests.get')
    def test_billing_requires_complete_pagination_and_rejects_other_requests(self,get):
        response=get.return_value.__enter__.return_value
        response.status_code=200
        event={'request_id':'request-1','endpoint_id':'fal-ai/qwen-image','timestamp':timezone.now().isoformat(),'unit':'image','cost_total':0.01}
        response.iter_content.return_value=[json.dumps({'billing_events':[event,event],'has_more':False}).encode()]
        self.assertEqual(len(fal_billing.fetch_events([self.job],timezone.now(),timezone.now())),1)
        event['request_id']='another-user'
        response.iter_content.return_value=[json.dumps({'billing_events':[event],'has_more':False}).encode()]
        with self.assertRaises(ValueError):fal_billing.fetch_events([self.job],timezone.now(),timezone.now())

    @override_settings(FAL_ONLY=True,FAL_KEY='test',S3_BUCKET='test',DAILY_PROVIDER_BUDGET_PAISE=10000,GEMINI_API_KEY='old-key')
    def test_fal_launch_excludes_google_and_nano(self):
        for slug,model in [('nano','fal:nano-banana'),('google','gemini-3.1-flash-image')]:
            Rate.objects.create(slug=slug,title=slug,kind='image',provider_model=model,enabled=True,unit_cost_paise=100)
        self.assertEqual(list(services.enabled_rates().values_list('pk',flat=True)),['qwen'])
