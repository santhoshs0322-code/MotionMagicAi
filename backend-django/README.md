# MotionMagic Django API

This is the dedicated MotionMagic backend. It replaces the shared CandyCraze/Node authentication module and owns MotionMagic accounts, wallet transactions, quotes, generation jobs, media metadata and verified one-time Google Play credit packs.

Google sign-in now issues a one-day access JWT plus an opaque, hashed-at-rest refresh session valid for at most 90 days. Android renews the access JWT silently and stores both tokens encrypted with Android Keystore. Refresh tokens rotate on use, preserve the original expiry date, and are revoked on sign-out. Previously issued app sessions without a refresh token require one fresh Google sign-in after updating.

Cloud services require the appropriate Google or fal.ai key, private S3 storage, positive configured costs and a positive provider budget. Missing configuration keeps the affected services out of `/api/v1/capabilities`. Play credit products additionally require billing configuration. Android now uploads references, obtains a quote, confirms a credit hold, polls jobs and downloads image/video results; native voice and editing remain local. See [PROVIDERS.md](PROVIDERS.md) for the complete model and environment mapping.

## Deploy on Render first

Use the root [`render.yaml`](../render.yaml) from branch `dev` to create the API using external PostgreSQL and Valkey services (such as Aiven). Supply `MOTION_AI_DATABASE_URL` and `MOTION_AI_REDIS_URL` privately in Render. It runs migrations before deploy, reads Render's HTTPS hostname and port, and generates separate secrets. The Blueprint includes a paid web service; apply it from your Render dashboard after reviewing the displayed cost. Follow [RENDER_SETUP.md](RENDER_SETUP.md) for exact setup, OAuth, Android connection and the later AWS cutover.

## Local verification

```powershell
$env:MOTION_AI_TESTING='true'
.\.venv\Scripts\python.exe manage.py migrate
.\.venv\Scripts\python.exe manage.py test
```

For a container-based development environment, copy `.env.example` to `.env`, replace local secrets and database/Redis hosts, then run `docker compose up --build`. Do not use those example credentials in AWS.

Build Android with the new HTTPS API address:

```powershell
powershell -ExecutionPolicy Bypass -File ..\scripts\build-android.ps1 -PmotionAiApiBaseUrl=https://api.example.com assembleRelease
```

The API preserves the existing Android login paths, so the app only needs a new base URL. Old Node JWTs intentionally do not carry over. Import MotionMagic accounts with `import_motion_accounts.py`, deploy, switch the API URL, and require users to sign in again.

## Financial rule

`MOTION_AI_MARKUP_BPS=15000` means a 150% markup: before store fees, customer charge = complete variable cost × 2.5. That produces a 60% gross margin at a zero store fee. With a configured store fee, the formula grosses up the price so net receipts still contain the 150% markup:

`charge = ceil(cost × (1 + markup) / (1 - store fee))`

Rates use integer paise. Quotes freeze model settings, cost, markup and fee assumptions for five minutes. Submission locks the wallet, holds the maximum charge, and uses an account-scoped idempotency key. Google/fal responses do not supply an authoritative INR invoice cost: successful jobs settle at the configured conservative cost allowance; the `actual_cost_paise` field records that allowance for these adapters. Gateway adapters may report actual costs. Failed jobs release the customer's entire hold. Ambiguous submissions are never repeated automatically. fal polling failures preserve the request ID and retry for up to 24 hours before refunding; ambiguous failed submissions still consume the daily budget allowance.

Google Play has two recurring plans and one consumable, permanent top-up: `motion_creator_monthly` grants 1,200 credits per verified billing period, `motion_legend_monthly` grants 2,400, and `motion_topup_500` grants 500 credits that never expire. A verified new Google account receives 200 welcome credits once. One customer credit has a US$0.005 face value (200 credits = US$1); provider allowances remain raw USD-cent values. Set `MOTION_AI_PLAY_ENABLED=true`, `MOTION_AI_SUBSCRIPTIONS_ENABLED=true` and `MOTION_AI_ENABLE_TOPUPS=true` only after the matching Play products and service-account verification are ready. The backend verifies the token and obfuscated account binding, encrypts tokens at rest, and uses immutable Play order IDs so retries cannot double grant. Configure the authenticated Google Pub/Sub RTDN callback to refresh renewals, cancellations and payment failures; a cancelled or expired membership returns model access to Free while permanent top-up credits remain in the wallet.

## Commands

- `python manage.py sync_provider_rates`: synchronize all Google and fal model/resolution/audio rates from integer-paise environment costs. Zero disables a rate. The older `sync_gemini_rates` remains Google-only.
- `python manage.py run_generation_worker`: claim jobs from the PostgreSQL outbox with `SKIP LOCKED`, submit/reconcile Google, fal or private-gateway jobs, stream outputs to S3 and settle them.
- `python manage.py reconcile_purchases`: scheduled Play status/consumption/refund reconciliation.
- `python manage.py cleanup_expired`: bounded cleanup for expired sessions and unused quotes.
- `python manage.py import_motion_accounts export.jsonl --apply`: import only the old MotionMagic users. Run without `--apply` first.

See [API.md](API.md) for endpoints and [AWS_ARCHITECTURE.md](AWS_ARCHITECTURE.md) for deployment and scaling boundaries.
