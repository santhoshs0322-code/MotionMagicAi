# MotionMagic AI browser studio

The web studio uses the same PostgreSQL `Account` (Google `sub`), `Wallet`, `Ledger`, `Job` and private S3 assets as Android. The welcome grant remains one-time across both platforms. The browser has an independent HttpOnly session with a fixed expiry; browser sign-out does not revoke Android sessions. Switching accounts clears its draft, selected files, previews and notifications. Browser tokens are not stored in localStorage.

Android continues using native Google sign-in, bearer JWTs, Google Play products and the existing API hostname. No Android source, package ID, Play prices, purchase verification, signing configuration or Play callback audience changes are required.

## Environment

### Private testing and visitor counts

To restrict the web studio before launch, set these in the server `.env`:

```ini
MOTION_AI_WEB_TEST_GATE_ENABLED=true
MOTION_AI_WEB_TEST_USERNAME=YOUR_TESTER_ID
MOTION_AI_WEB_TEST_PASSWORD=YOUR_UNIQUE_16_OR_MORE_CHARACTER_PASSWORD
MOTION_AI_WEB_VISITOR_TRACKING_ENABLED=true
```

Use a separate testing password, not your owner, Google or PayU password. HTTPS browsers display an ID/password prompt before the studio; Google login is still required afterward. Native Android bearer authentication, owner login, health checks, and PayU callbacks keep their existing access rules. The gate also protects existing browser-session API access. Browsers can retain Basic credentials until their session closes.

To make the web studio public later, set `MOTION_AI_WEB_TEST_GATE_ENABLED=false` and recreate the containers. Visitor tracking can remain enabled independently. No Android rebuild is needed.

The owner-only `/owner/visitors/` page shows date-filtered estimated daily visitors, total page views, and admitted views. It counts GET page loads on `/` and `/app/`, including password prompts; it excludes APIs, assets, owner pages and callbacks. Anonymous signed cookies deduplicate browsers within a day. Period totals add daily visitors, so they are **not unique people across the whole period**. Bots and cookie/device changes can affect counts. No IP addresses, raw user agents or Google identities are retained in these records. Include this anonymous visitor-cookie use in your published privacy/cookie notice. Set `MOTION_AI_WEB_VISITOR_TRACKING_ENABLED=false` to stop collection.

After pulling this change, apply migration `0025` via Compose startup and recreate services:

```bash
docker compose -f compose.aws.yaml up -d --build
# If you enabled the payment worker:
docker compose -f compose.aws.yaml --profile web-payments up -d --build payments
```

Add these to `backend-django/.env`; all new variables use `MOTION_AI_`:

```ini
MOTION_AI_WEB_ENABLED=true
MOTION_AI_WEB_PUBLIC_URL=https://motionmagicai.in
MOTION_AI_PAYU_ENABLED=false
MOTION_AI_PAYU_MODE=test
MOTION_AI_PAYU_ALLOW_LIVE=false
MOTION_AI_PAYU_KEY=YOUR_PAYU_MERCHANT_KEY
MOTION_AI_PAYU_SALT=YOUR_PAYU_MERCHANT_SALT
MOTION_AI_WEB_PACK_2500_PRICE_PAISE=0
MOTION_AI_WEB_PACK_5000_PRICE_PAISE=0
MOTION_AI_WEB_PACK_10000_PRICE_PAISE=0
MOTION_AI_WEB_PACK_20000_PRICE_PAISE=0
```

Choose each web retail price independently. Values are integer INR paise: for example, a chosen price of INR 1499.00 is `149900`. Zero disables that web pack. These are not copied to/from Google Play and they do not change generation credit quotes. Run `sync_web_packs` after changing them; Compose startup does this automatically. Web pricing remains unconfigured until you enter your amounts.

For sandbox checkout, enter the PayU **test** key/salt and set `MOTION_AI_PAYU_ENABLED=true`. Test successes are shown as test payments and never add live credits or cash receipts. After sandbox verification, change to your **live** key/salt, `MOTION_AI_PAYU_MODE=live` and `MOTION_AI_PAYU_ALLOW_LIVE=true`. Existing orders retain their original environment and prices.

Keep `MOTION_AI_CURRENT_SITE_URL=https://api.motionmagicai.in`, the Google client ID, Play billing keys, package, Play flags and Pub/Sub audience as they are. The configured web HTTPS origin is added to Django's exact allowed hosts and CSRF origins; it introduces no wildcard CORS or cookie domain.

## Google sign-in

In Google Cloud Console, edit the **existing Web OAuth client used by Android's server ID-token flow**. Add **Authorized JavaScript origin** `https://motionmagicai.in`. No client secret or Android signing changes are needed. The GIS popup callback uses the same ID-token audience, Google subject, a server-bound nonce and CSRF protection. Add a separate exact origin only if you intentionally host the studio elsewhere.

If Google displays **401 invalid_client / no registered origin**, open Google Auth Platform → Clients (or APIs & Services → Credentials), select the **Web application** whose client ID matches `/auth/motion-ai/config`'s `googleWebClientId`, and add `https://motionmagicai.in` under **Authorized JavaScript origins**, then save. Use the origin only: no `/app/` path or trailing slash. Keep existing origins and Android clients. If you serve the studio directly at `www`, also add `https://www.motionmagicai.in`; the default Caddy setup redirects it to the apex. This popup flow does not need a new redirect URI. Allow Google's configuration change to propagate, then reload in a fresh browser window. If the OAuth app is in Testing, add your test Google accounts under Audience → Test users as well; that is separate from registering the JavaScript origin.

## Deploy on the existing EC2 host

```sh
cd ~/MotionMagicAi
git pull origin dev
cd backend-django
nano .env
docker compose -f compose.aws.yaml up -d --build
docker compose -f compose.aws.yaml exec -T api python manage.py configure_web_cors
docker compose -f compose.aws.yaml --profile web-payments up -d --build payments
```

Migration `0024_web_google_payu` creates independent web session, pack and order tables. It also permits a system-recorded cash entry, so verified live PayU receipts and refunds appear in the existing owner cash reports. The setup container applies migrations and syncs the web packs before API startup.

`configure_web_cors` modifies only the bucket rule named `MotionMagicWeb`, preserving other rules. The EC2 role needs `s3:GetBucketCORS` and `s3:PutBucketCORS` for this setup command. Normal app operation does not need these configuration permissions. The bucket remains private; uploads go directly from the browser to an exact-size, expiring S3 POST form.

Caddy now serves the browser studio on `https://motionmagicai.in/` and `/app/`, redirects `www` to that origin, and retains the existing external Terms/Privacy destinations. Its A records must point at this instance's Elastic IP and ports 80/443 must remain open. `https://api.motionmagicai.in` continues serving Android and Google Play. Browser navigation to the API root redirects to the configured web studio; API routes do not redirect.

The optional `payments` service is separate from AI generation workers. Keep it running when live PayU checkout is enabled. It checks up to five due orders at a time and recovers pending payments even if the browser closes. Pending/failed orders are checked for seven days; verified payments are checked for refunds for 180 days. Do not toggle a live deployment back to test credentials while live orders need reconciliation. You can run one recovery pass with `python manage.py reconcile_payu`.

Turning off `MOTION_AI_PAYU_ENABLED` or the web UI stops new checkout. Existing signed callbacks and the payment worker can still settle prior orders and refunds while the original mode/key/salt remain configured. Keep those credentials and the payment worker available until reconciliation is finished.

## PayU endpoints

- Hosted checkout success/failure return: `https://motionmagicai.in/app/payments/payu/return/`
- Payment webhook: `https://motionmagicai.in/app/payments/payu/webhook/`. It acknowledges verified notifications with HTTP 200; preserve existing game webhook configurations.
- Refund status callback: `https://motionmagicai.in/app/payments/payu/refund/` (enable it in PayU with your account manager if required).

Checkout requests use the existing PayU SHA-512 request/reverse-response pattern, then independently query `verify_payment`. Amount, Google-bound account, product, transaction ID, mode and PayU payment ID must match. Successful refunds are independently fetched with `getAllRefundsFromTxnIds`. Payment ID uniqueness, account wallet locks and immutable ledger references prevent duplicate credit grants. Verified late payments remain recoverable after checkout expiry. Refunds revoke the proportional purchased credits, including debt if they were already spent; they never delete the welcome-credit ledger entry.

Owner console: **Web payments** lists account, price snapshot, credits, environment, payment ID, state and refunds. Verified **live** gross receipts/refunds also enter the cash book. Record gateway fees, taxes and settlement adjustments from statements; do not record the same bank payout as another gross receipt. No PayU fee percentage is guessed.

## Browser creation and projects

Home and templates consume the owner showcase feed. Image, photo-to-video, text-to-video, continue-story and Long Story controls use enabled server capabilities. Local estimates use the catalogue; Create requests an authoritative quote, then confirmation, direct-to-S3 uploads and an idempotent job submission. Unknown submission outcomes can be recovered with the same saved quote/assets/request key. Cancel upload resets only that attempt.

My Projects reads account-scoped paginated database results, with a bounded active-job status refresh while jobs run. It never polls fal directly. Completed media is already saved by the worker; previews use expiring owned URLs and browser downloads stream directly from S3. Local-only Android files remain on their device. Account changes, refresh and web sign-out do not expose another account's draft, wallet, files or orders.

## Verification

Backend regression tests cover Android compatibility, identity/welcome-credit sharing, cookie expiry, cross-tab account binding, CSRF, separate prices, owned orders, verified settlement, duplicate recovery, timeouts, sandbox isolation and refunds. Headless browser checks cover desktop/mobile themes, model settings, half-minute durations, quote/upload/queue/recovery, cancellation, project downloads, PayU routing and clearing account data. Providers and payments are mocked in verification; no live purchase or generation was made.

References: [Google ID-token verification](https://developers.google.com/identity/gsi/web/guides/verify-google-id-token), [GIS JavaScript nonce and popup API](https://developers.google.com/identity/gsi/web/reference/js-reference), [PayU hashing](https://docs.payu.in/docs/hashing-request-and-response), [Verify payment](https://docs.payu.in/reference/verify_payment_api), [Refund verification](https://docs.payu.in/reference/get_all_refunds_from_transaction_ids_api).

For local browser checks, install the development-only `playwright` Python package, start a **local testing-only** Django server on `127.0.0.1:8877`, and run `python scripts/test-web-ui.py` from the repository root. Use a local SQLite test database and `MOTION_AI_TESTING=true`, never production credentials. The script uses installed Chrome or Playwright Chromium; `MOTION_AI_WEB_TEST_BROWSER` can select another Chrome executable. It mocks Google, wallet/catalog data, uploads, generation and PayU, saves desktop/mobile screenshots under `artifacts/web-review`, and never initiates a real payment or generation.
