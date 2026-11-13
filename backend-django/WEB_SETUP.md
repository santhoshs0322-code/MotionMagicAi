# MotionMagic AI web studio

The web uses Google sign-in and the same Account, Wallet, Ledger, cloud Jobs and private S3 media as Android. There is no web testing ceiling, arrival questionnaire, generation survey or automatic testing-to-pricing redirect. The browser has its own HttpOnly session; logout does not revoke Android. Account changes clear private drafts and files.

## Shared welcome credits

```ini
MOTION_AI_WELCOME_CREDITS=300
MOTION_AI_WEB_TEST_GATE_ENABLED=false
MOTION_AI_WEB_ENABLED=true
MOTION_AI_WEB_PUBLIC_URL=https://motionmagicai.in
```

A new Google account receives 300 one-time welcome credits. Both Android and web use this same setting and immutable welcome ledger. Existing balances, purchased credits and earlier 100/200-credit welcome grants are preserved; logging in again never grants a replacement or refills the wallet. Changing the default is not a migration of existing balances.

The retired `MOTION_AI_WEB_TRIAL_MODE` and `MOTION_AI_WEB_WELCOME_CREDITS` environment variables are ignored and can be removed. Historical survey answers and web-generation records remain in the owner report, but the retired survey endpoints no longer accept submissions. Traffic and web-account activity reporting continue.

## Google login

Use the existing Web application OAuth client matching `/auth/motion-ai/config`'s `googleWebClientId`. Add `https://motionmagicai.in` under Authorized JavaScript origins in Google Cloud Console. Use only the origin: no path or trailing slash. Keep existing origins and Android clients. GIS uses a popup ID-token callback, nonce and CSRF; no new redirect URI or browser client secret is needed. If Google shows `invalid_client / no registered origin`, check this exact Web client and origin. Add test users under Audience if the Google OAuth app is in Testing.

Keep `MOTION_AI_CURRENT_SITE_URL=https://api.motionmagicai.in` and the existing Play Pub/Sub audience. Caddy serves the studio on the apex and Android on the API hostname. Terms/Privacy redirect to the existing Gamixtv legal pages.

## PayU sandbox test payment

Use your PayU **test merchant** credentials:

```ini
MOTION_AI_PAYU_ENABLED=true
MOTION_AI_PAYU_MODE=test
MOTION_AI_PAYU_ALLOW_LIVE=false
MOTION_AI_PAYU_KEY=YOUR_PAYU_TEST_MERCHANT_KEY
MOTION_AI_PAYU_SALT=YOUR_PAYU_TEST_MERCHANT_SALT
```

After `sync_web_packs`, Add Credits shows a separate **Test payment INR 1** panel. It uses `web-sandbox-test`, a server-controlled one-rupee test SKU. Enter a phone number, click Test payment, then continue to PayU's sandbox. Test verification records the order but **never grants spendable credits or live cash receipts**. No fal request is made by checkout. The test product is excluded from ordinary credit packs and cannot be purchased in live mode even if a stale database row is still enabled.

The existing configured 2,500 / 5,000 / 10,000 / 20,000 packs can also be checked in test mode. Their web retail prices remain independent of Play:

```ini
MOTION_AI_WEB_PACK_2500_PRICE_PAISE=0
MOTION_AI_WEB_PACK_5000_PRICE_PAISE=0
MOTION_AI_WEB_PACK_10000_PRICE_PAISE=0
MOTION_AI_WEB_PACK_20000_PRICE_PAISE=0
```

Zero means unavailable, not free. Use your chosen integer INR paise amounts (INR 1499.00 would be 149900). Do not infer fal cost or profit from the retail price. `/app/pricing/` displays synchronized real prices before Google login. The INR 1 sandbox panel works independently of these four prices, so they may remain zero during payment testing.

For live sales later, use live key/salt, set `MOTION_AI_PAYU_MODE=live` and explicitly set `MOTION_AI_PAYU_ALLOW_LIVE=true`, then resynchronize packs. This hides/disables the sandbox SKU. Existing orders retain their environment and immutable credit/price snapshots. Keep original credentials available to settle outstanding payments/refunds; a different environment cannot verify them.

## Deploy on AWS

```bash
cd ~/MotionMagicAi
git pull origin dev
cd backend-django
nano .env
docker compose -f compose.aws.yaml --profile web-payments up -d --build
docker compose -f compose.aws.yaml exec -T api python manage.py sync_web_packs
```

Compose setup applies existing migrations and synchronizes rates/packs. This change needs no new schema migration. The separate payments worker reconciles web orders without holding up the AI worker. Do not change Android Play products, package ID or subscription callback settings. The optional Android UI text correction to 300 welcome credits appears in the next Android build; web changes do not require rebuilding Android.

For a first web deployment, add `https://motionmagicai.in` as the Google JavaScript origin and run:

```bash
docker compose -f compose.aws.yaml exec -T api python manage.py configure_web_cors
```

This merges an exact-origin S3 CORS rule without making the bucket public. The server identity needs GetBucketCors/PutBucketCors permission. Files upload directly to S3 and downloads stream from owned signed URLs. Public static assets have fingerprinted URLs; private API/session responses are not cached.

## Payment callbacks and verification

- Browser success/failure return: `https://motionmagicai.in/app/payments/payu/return/`
- Payment webhook: `https://motionmagicai.in/app/payments/payu/webhook/` (verified events acknowledge HTTP 200)
- Refund callback: `https://motionmagicai.in/app/payments/payu/refund/`

PayU hashes are checked server-side, then `verify_payment` independently verifies the account, product, transaction, environment, amount and captured status. Successful refunds are independently fetched before granting/revoking credits. Wallet locks, unique payment IDs and immutable ledger references prevent duplicate grants. Partial refunds proportionally revoke purchased credits; late captured payments remain recoverable. No merchant salt or payment credentials are exposed to the browser.

Disabling new PayU sales does not disable reconciliation of earlier orders while the original credentials remain configured. Live verified receipts/refunds appear in owner Cash records; gateway fees, taxes and settlement adjustments must come from statements. Do not count the same bank payout twice. Sandbox orders are marked test and never appear as live receipts.

## Owner reports and verification

`/owner/visitors/` is owner-only and includes anonymous daily browser/page-view estimates, source/device/landing categories, active web accounts, new accounts, sign-ins, restored-session visits, web job outcomes and historical feedback. Signed cookies deduplicate browser/day counts, not people across devices. No raw IP, full referrer URL or raw user agent is stored. Set `MOTION_AI_WEB_VISITOR_TRACKING_ENABLED=false` to stop anonymous page counters. Keep the privacy notice consistent with analytics and account activity records.

Run Django tests with `MOTION_AI_TESTING=true`. `scripts/test-web-ui.py` uses mocked Google, quotes, jobs, S3 and PayU endpoints against a local TESTING server at port 8877; it does not perform purchases or AI generations. It checks normal login, account isolation, creation/recovery/cancellation, mobile/desktop themes, pricing and sandbox/live UI isolation.

Official references:
- https://developers.google.com/identity/gsi/web/guides/get-google-api-clientid
- https://developers.google.com/identity/gsi/web/guides/verify-google-id-token
- https://docs.payu.in/docs/hashing-request-and-response
- https://docs.payu.in/reference/verify_payment_api
- https://docs.payu.in/reference/get_all_refunds_from_transaction_ids_api
