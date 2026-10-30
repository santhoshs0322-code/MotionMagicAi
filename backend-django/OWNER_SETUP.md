# Private owner finance panel

After deploying `dev`, open https://motionmagicai.onrender.com/owner/ . This is a separate browser login. App Google accounts, app JWTs, ordinary Django staff and other superusers cannot access it. The configured username must also be an active Django superuser. Leaving the username empty disables all owner routes with HTTP 404.

## Render environment

Keep existing database, Redis, signing, Google **login**, S3 and pricing settings. Add these on the API and worker (replace placeholders privately):

```dotenv
MOTION_AI_FAL_ONLY=true
MOTION_AI_FAL_KEY=YOUR_FAL_GENERATION_KEY
MOTION_AI_OWNER_USERNAME=motion-owner
MOTION_AI_FAL_BILLING_KEY=YOUR_FAL_BILLING_READ_KEY
MOTION_AI_USD_INR_RATE=0
MOTION_AI_MARKUP_BPS=15000
MOTION_AI_CSRF_TRUSTED_ORIGINS=https://motionmagicai.onrender.com
```

`USD_INR_RATE=0` deliberately disables automatic bill reconciliation. Replace it with your verified INR per USD conversion rate (up to six decimal places). No exchange rate is guessed. The billing key needs fal's BILLING preset / billing-event read permissions; a normal generation API key may not have these permissions. Both keys belong only on the server. A Gemini generation key is not required. Nano Banana and Google video are excluded even if old keys remain configured; adapters remain for already submitted jobs.

## Setup without Shell access

In Render ? API service ? Environment, set:

```dotenv
MOTION_AI_OWNER_USERNAME=motion-owner
MOTION_AI_OWNER_BOOTSTRAP_PASSWORD=REPLACE_WITH_A_UNIQUE_RANDOM_PASSWORD
```

Use a unique password of 16?256 characters, not all numeric and not containing the username. Do not paste your real password in chat or commit it. Keep the username in the environment permanently.

For a manually configured service, set its Pre-Deploy Command to:

```sh
python manage.py migrate --noinput && python manage.py sync_provider_rates && python manage.py bootstrap_owner
```

The repository Blueprint already includes this command. If your Render plan does not provide Pre-Deploy Command, set the web service's Docker Command to:

```sh
sh -c 'python manage.py migrate --noinput && python manage.py sync_provider_rates && python manage.py bootstrap_owner && exec gunicorn --config config/gunicorn.conf.py config.wsgi:application'
```

This startup fallback is for the initial single-instance Render deployment; use a separate migration/deployment job before scaling to multiple instances. Do not put this web command on the generation worker.

Save and deploy `dev`. Once the owner-created log appears, log into `/owner/` with the username and chosen password. Then delete `MOTION_AI_OWNER_BOOTSTRAP_PASSWORD` from Render and save/redeploy. Your account and hashed password remain in PostgreSQL. Repeated deployments do not overwrite an existing owner's password, and an existing ordinary account is never promoted. Missing bootstrap settings safely skip creation.

## Optional shell setup

Run in the Render API shell after deployment:

```sh
python manage.py migrate --noinput
python manage.py sync_provider_rates
python manage.py createsuperuser --username motion-owner
```

Choose a strong password at the prompt; it is not stored in an environment variable or the app. Use `python manage.py changepassword motion-owner` to reset it. Sessions expire after one hour, use secure HTTP-only cookies in production, and all writes require CSRF tokens. Failed login attempts are throttled. Do not enable TESTING or DEBUG in production.

## What is recorded

- Day, month and user reports, using India time and inclusive date filters.
- Every wallet hold, charge, release, purchase credit and refund; balances and pending jobs.
- Frozen generation quotes, model settings, cost allowances, markup, fee estimates, provider request IDs and final charges.
- Actual fal billed USD converted to INR, with the original amount and conversion rate retained. Missing billing rows remain **unreconciled**, never assumed free.
- Append-only invoice cost corrections and statement entries for actual customer receipts, refunds, store fees, business expenses and provider top-ups. Entries can be reversed with a reason; the original remains visible.
- CSV exports and owner login, export and financial-change audit events. Provider keys, purchase tokens and user prompts are not exposed.

The running generation worker checks billing every five minutes in batches of 25 recent jobs. A shared Redis lock reduces duplicate syncs. Checked jobs are eligible again after six hours; the default lookback is seven days. For older or delayed bills, run:

```sh
python manage.py sync_fal_costs --days 89 --limit 1000
```

Repeat as necessary to process remaining jobs. fal permits bounded billing windows; for jobs outside this window, record the verified INR invoice cost manually. A manual invoice takes priority over later automatic billing. Refreshes preserve each job's original FX valuation. Reconciliation never changes customer wallet charges. The provider budget still uses conservative configured allowances, not delayed invoices.

**Wallet credit spending is not a bank receipt.** Play purchase verification grants credits but does not provide a complete settlement statement. Record actual gross receipts, refunds, fees, taxes and other expenses from your statements; optionally associate entries with an app user and purchase. If recording a net bank receipt, do not deduct its fees again. Provider top-ups are cash movements, not an extra job expense. The dashboard labels estimated job margin, reconciled provider costs and recorded cash movement separately; it cannot claim final net profit from incomplete statements.

150% markup means `cost × 2.5`, or 60% gross margin before additional expenses. Existing store-fee gross-up remains in effect. Example: ₹10 cost → ₹25 before fees, ₹15 gross contribution. Review nonzero per-model costs, overhead and daily budget before enabling generation; a fal key alone does not make zero-cost rates available.

## Limits and operations

Amounts are integer paise; USD conversion uses decimal arithmetic and rounds once per job. Dates on old settled jobs are backfilled from their settlement ledger entry, falling back to their previous updated timestamp if that ledger entry is missing. New settlements record their date explicitly.

Detail pages paginate 50 rows. User breakdowns show at most 200 groups per source and explicitly flag truncation; overall totals include all matching rows. Reports allow at most 367 days and exports at most 10,000 rows; narrow dates or select a user for larger histories. Aggregation runs in the database. These safeguards are not a claim of one-million-concurrent-user capacity: production capacity still requires load testing, database sizing and worker/provider quota planning. Back up PostgreSQL and monitor missing billing reconciliation and worker errors. Schedule Django `clearsessions` periodically to remove expired sessions.

fal billing reference: https://fal.ai/docs/platform-apis/v1/models/billing-events
