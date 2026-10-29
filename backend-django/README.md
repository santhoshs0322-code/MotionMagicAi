# MotionMagic Django API

This is the dedicated MotionMagic backend. It replaces the shared CandyCraze/Node authentication module and owns MotionMagic accounts, wallet transactions, quotes, generation jobs, media metadata and verified one-time Google Play credit packs.

It is deliberately closed by default. With `MOTION_AI_GATEWAY_URL`, S3, a positive provider budget, enabled rate rows and enabled Play products absent, `/api/v1/capabilities` returns no cloud services and `/api/v1/billing/products` returns no products. The Android app continues to provide its local tools.

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

Rates use integer paise. Quotes freeze cost, markup and fee assumptions for five minutes. Submission locks the wallet, holds the maximum charge, and uses an account-scoped idempotency key. Settlement charges from reported actual cost and releases the remainder. Uncertain provider requests remain held for reconciliation instead of being blindly retried.

Google Play products are one-time credit packs only. Products remain hidden until Play verification is enabled. The backend verifies the token, product, purchase state, consumption state, quantity and obfuscated account ID before granting credits; token hashes are unique, token values are encrypted, and retries cannot grant twice. Refund reconciliation creates a negative ledger entry and can produce account debt if credits were already spent. Subscriptions are not advertised or implemented.

## Commands

- `python manage.py run_generation_worker`: claim jobs from the PostgreSQL outbox with `SKIP LOCKED`, submit/reconcile with the private gateway, and settle them.
- `python manage.py reconcile_purchases`: scheduled Play status/consumption/refund reconciliation.
- `python manage.py cleanup_expired`: bounded cleanup for expired sessions and unused quotes.
- `python manage.py import_motion_accounts export.jsonl --apply`: import only the old MotionMagic users. Run without `--apply` first.

See [API.md](API.md) for endpoints and [AWS_ARCHITECTURE.md](AWS_ARCHITECTURE.md) for deployment and scaling boundaries.
