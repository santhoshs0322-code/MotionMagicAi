# Render first, AWS later

Deploy the dedicated Django backend from this repository. The root [`render.yaml`](../render.yaml) creates a Docker web service in Singapore and accepts your external PostgreSQL and Valkey connection URLs (such as Aiven) as private settings. Application settings retain the `MOTION_AI_` prefix. The same container can move to AWS later.

This is an initial staging setup, not a capacity claim for one million concurrent users. The Blueprint selects **paid web compute**; your database and cache are managed separately. Render shows the cost before you apply it. Account data, wallets, ledger entries and the job outbox live in PostgreSQL; Valkey holds shared rate-limit and short-lived cache data. Load-test before public production traffic.

## Deploy from GitHub

1. Push the backend and root `render.yaml` to the repository's `dev` branch.
2. In Render, choose **New → Blueprint**, connect `santhoshs0322-code/MotionMagicAi`, select **dev**, and use `render.yaml` at the repository root.
3. Enter `MOTION_AI_DATABASE_URL` using the full external database Service URI, including `?sslmode=require`. Enter the full TLS Valkey Service URI as `MOTION_AI_REDIS_URL`, beginning with `rediss://`. Review the resource and apply. Render generates two different secrets, builds the container and runs `python manage.py migrate --noinput` before starting the web service. For an existing Blueprint, set both URLs in the web service's Environment page yourself; `sync: false` only prompts during initial creation.
4. Open the web service and copy its actual HTTPS URL. Render may add a suffix to the service name. Django reads the exact hostname from `RENDER_EXTERNAL_HOSTNAME`, so no guessed hostname or wildcard is needed.
5. Check the endpoints below before configuring Android or enabling sign-in.

The selected paid web plan supports the [pre-deploy migration command](https://render.com/docs/deploys#pre-deploy-command). This API serves JSON only, so it does not need a static-site publish directory or `collectstatic`. Android-only changes outside `backend-django/` do not trigger a backend build. Blueprint configuration changes still require a Blueprint sync.

If you create the web service manually instead, use these values and supply its database/cache settings yourself:

| Render field | Value |
|---|---|
| Branch | `dev` |
| Language/runtime | Docker |
| Root directory | `backend-django` |
| Dockerfile path | `./Dockerfile` |
| Docker build context | `.` |
| Docker command | Leave blank; the Dockerfile runs Gunicorn |
| Pre-deploy command | `python manage.py migrate --noinput && python manage.py sync_provider_rates` |
| Health check | `/ready` |
| Region | Keep the external PostgreSQL and Valkey services nearby where possible |

Do not attach this service to CandyCraze's database or reuse its secrets. Existing Node sessions do not authenticate against MotionMagic Django.

## Environment settings

The Blueprint supplies the following. Do not copy actual secret values into Git or the APK.

| Setting | Source / initial value |
|---|---|
| `MOTION_AI_DJANGO_SECRET` | Render-generated random secret |
| `MOTION_AI_JWT_SECRET` | A separately generated random secret |
| `MOTION_AI_DATABASE_URL` | Your external PostgreSQL Service URI, entered privately in Render |
| `MOTION_AI_REDIS_URL` | External TLS Valkey Service URI, entered privately in Render |
| `MOTION_AI_PROXY_MODE` | `render` |
| `MOTION_AI_DEBUG`, `MOTION_AI_TESTING` | `false` |
| `MOTION_AI_SSL_REDIRECT` | `true` |
| `MOTION_AI_WEB_WORKERS`, `MOTION_AI_WEB_THREADS` | `1`, `2` for the initial database's 15-connection limit |
| `MOTION_AI_AUTH_ENABLED` | `false` until Google OAuth is ready |
| `MOTION_AI_MARKUP_BPS` | `15000` (150% markup, cost × 2.5 before fee gross-up) |
| `MOTION_AI_STORE_FEE_BPS` | `0`; set the actual applicable fee before billing |
| `MOTION_AI_DAILY_PROVIDER_BUDGET_PAISE` | `0` |
| `MOTION_AI_PLAY_ENABLED` | `false` |

`PORT` and `RENDER_EXTERNAL_HOSTNAME` are platform-owned variables automatically supplied by Render, not new application settings. Gunicorn reads `PORT` and defaults to 8000 when it is absent. For a custom domain, additionally set `MOTION_AI_ALLOWED_HOSTS=api.your-domain.com` and `MOTION_AI_CSRF_TRUSTED_ORIGINS=https://api.your-domain.com`; the exact Render hostname remains allowed.

For Aiven, use the full Service URI in `MOTION_AI_DATABASE_URL`; separate host/user/password/database variables are unnecessary. Keep `sslmode=require` in the URL. For server certificate verification, upload Aiven's project CA as a Render secret file and use `sslmode=verify-full` with `sslrootcert` pointing to that file; see [Aiven's TLS documentation](https://aiven.io/docs/platform/concepts/tls-ssl-certificates). URL-encode reserved characters in the password when assembling a URI yourself. Never commit the actual URI or put it in Android. If the earlier Blueprint already created a Render database, this change does not migrate or delete its data; decide separately which database to retain before accepting real writes.

Use Aiven's complete `rediss://user:password@host:port` Service URI as `MOTION_AI_REDIS_URL`. The double `s` means Redis protocol over TLS. Do not change it to `redis://`, split it into separate variables, add PostgreSQL query parameters, or paste it into Android. If an earlier Blueprint created a Render Key Value instance, changing this file does not delete that resource; remove it separately in Render only after `/ready` succeeds with Aiven Valkey.

Keep one API worker and two threads initially. This is not a global connection cap: overlapping deploys, migrations, database consoles and future background workers also use connections. The app closes database connections after requests (`CONN_MAX_AGE=0`). The 15-connection database is for initial testing, not the earlier million-user target.

Render's generated secrets contain 256 bits of randomness. Django's optional `check --deploy` also applies a 50-character length heuristic, so its 44-character generated Django secret can report `security.W009`. To satisfy that check, replace only `MOTION_AI_DJANGO_SECRET` in Render with a securely generated value of at least 50 characters (for example, `python -c "import secrets; print(secrets.token_urlsafe(64))"` locally), then redeploy. Keep it private; do not replace the JWT secret during routine deploys, as that signs out existing sessions.

Only use `MOTION_AI_PROXY_MODE=render` on Render. It enables trusted HTTPS proxy handling and reads the rightmost forwarded address for login throttling, ignoring client-supplied prefixes. If additional proxies group users under one forwarded address, the throttle is conservative; verify the deployed routing before increasing traffic. AWS uses `alb` with private ingress restricted to its load balancer. Existing `MOTION_AI_BEHIND_ALB=true` configurations remain supported when no explicit proxy mode is provided.

## Verify the first deploy

Replace the placeholder with the URL shown by Render:

```powershell
$motionAiUrl = 'https://YOUR-ACTUAL-SERVICE.onrender.com'
Invoke-RestMethod "$motionAiUrl/health"
Invoke-RestMethod "$motionAiUrl/ready"
Invoke-RestMethod "$motionAiUrl/auth/motion-ai/config"
Invoke-RestMethod "$motionAiUrl/api/v1/capabilities"
```

Expected initial behavior:

- `/health`: `ok=true`, `service=motionmagic-django`. A different service name means the wrong code or URL was deployed.
- `/ready`: HTTP 200 with `ok=true`, confirming database/cache connectivity.
- `/auth/motion-ai/config`: `appId=motionmagic`, `enabled=false`.
- `/api/v1/capabilities`: working local tools, an empty `cloud` list and subscriptions disabled.
- `/api/v1/wallet` without a bearer token: HTTP 401.

There is no `/` homepage or Django admin in this API. A 404 at the root URL does not indicate a failed deploy. A 301 loop indicates missing/incorrect proxy mode; a 400 host error indicates a missing allowed hostname; a 503 from `/ready` means its database/cache dependency is unavailable. Use Render logs for the underlying failure without posting credentials.

## Enable Google login and connect Android

Create the Google Web OAuth client and Android OAuth client in the same Google Cloud project. Register Android package `com.gamixtv.MotionMagicAI` and the SHA-1 certificate for the build you will install. The debug certificate is different from Play App Signing; both need their own registration when used. Obtain local signing information with `scripts/build-android.ps1 signingReport` from the repository root.

For the package rename, register the new package in your Android OAuth client with the signing SHA-1. Keep the Web OAuth client ID if the Google project is unchanged. If Render already defines `MOTION_AI_PLAY_PACKAGE`, change it to `com.gamixtv.MotionMagicAI`; existing environment values override the new code default. Android treats the renamed package as a separate installation, so old local projects and saved sign-in do not automatically transfer.

Add `MOTION_AI_GOOGLE_CLIENT_ID=<your Web client ID>.apps.googleusercontent.com` in the Render web service, then enable `MOTION_AI_AUTH_ENABLED=true`. When retaining this setting, also change its Blueprint value to `"true"` before the next Blueprint sync; declarative values are reapplied during sync. The client ID is public configuration, not the Google client secret. No client secret is required for this native ID-token flow.

Build Android from the repository root with the deployed address:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build-android.ps1 -PmotionAiApiBaseUrl=https://YOUR-ACTUAL-SERVICE.onrender.com assembleDebug
```

Confirm `/auth/motion-ai/config` reports enabled and returns the correct Web client ID. Then test a real sign-in, `/me`, logout/revocation and sign-in again from the installed app. No provider keys or production purchases are needed to verify authentication.

## Enable image and video generation

The Blueprint does not automatically create a generation worker because it is another paid Render service. The Google key serves the implemented Nano Banana models and Gemini Omni video. `MOTION_AI_FAL_KEY` serves the other image models, Kling O3 and PixVerse V6 through fal.ai. Follow [PROVIDERS.md](PROVIDERS.md) for the full mapping, per-resolution/audio cost variables, rollout and limits.

1. Create a paid Gemini API key in Google AI Studio. Put it only in the API and generation worker as `MOTION_AI_GEMINI_API_KEY`; never paste it into Android, Git, chat, logs or screenshots.
2. Create a private S3 bucket with Block Public Access enabled. On Render create an IAM user limited to that bucket/prefix and set `MOTION_AI_S3_BUCKET`, `MOTION_AI_AWS_REGION`, `MOTION_AI_AWS_ACCESS_KEY_ID`, and `MOTION_AI_AWS_SECRET_ACCESS_KEY` on both services. On ECS later, remove access-key variables and use a task role.
3. Set `MOTION_AI_DAILY_PROVIDER_BUDGET_PAISE` on both services after reviewing costs. It limits configured cost allowances; it does not independently measure the provider bill. The budget is split into 64 shards, so allow at least 64 times the largest single-job cost. Keep it zero until the worker and prices are ready.
4. On the API only, set the current complete provider cost in paise for each model/resolution you will sell. Zero keeps it hidden. Start with `MOTION_AI_GEMINI_NANO_21_1K_COST_PAISE` for images and `MOTION_AI_GEMINI_OMNI_720P_COST_PAISE_PER_SECOND` for video. The complete list is in `.env.example`.
5. Keep `MOTION_AI_MARKUP_BPS=15000`. The quote engine charges 2.5 times complete variable cost before configured store-fee gross-up. Include input-token cost, currency conversion, S3/egress and a safety allowance through the cost/overhead settings; do not enter only the advertised output price.
6. Deploy the API migration, then create a Render Background Worker from branch `dev`, root `backend-django`, the same Dockerfile, and command `python manage.py run_generation_worker`. Copy the database, Valkey, S3, Google/fal keys, budget and pricing settings from the API; use `MOTION_AI_ALLOWED_HOSTS=localhost`, `MOTION_AI_PROXY_MODE=none`, and `MOTION_AI_SSL_REDIRECT=false`. Once both run the new code, enable the reviewed costs and run `sync_provider_rates` (or redeploy). Confirm `/api/v1/capabilities` lists only configured services. Test a low-cost generation with a funded test wallet before enabling Play billing.

The worker uses a 20-minute database lease, never automatically repeats an ambiguous paid Gemini request, pessimistically consumes its reserved provider budget on uncertain failure, and releases the customer's held credits when no output is delivered. Omni video uses URI delivery and streams into S3, so memory usage is bounded by multipart chunks instead of video size.

Kling O3, PixVerse V6, Qwen, GPT Image, Seedream and original Nano Banana now have queued fal adapters and mocked contract/financial tests. Live requests still need your fal account access, key, provider balance and configured cost allowances. Google does not unlock these fal routes.

Run `python manage.py cleanup_expired` periodically. Configure `reconcile_purchases` only after real Play verification is enabled. Android credit-pack checkout, subscriptions and provider-invoice cost reconciliation remain separate work.

## Move to AWS after validation

Use the same Docker image with ECS/Fargate, PostgreSQL-compatible RDS/Aurora, a shared Redis-compatible cache and a private S3 bucket. Set `MOTION_AI_PROXY_MODE=alb`, exact AWS/custom hosts and the AWS database/cache URLs. Keep signing/encryption secrets consistent if existing sessions and encrypted records must remain usable.

Before cutover, freeze writes and workers, back up and restore PostgreSQL, preserve account IDs and all financial tables, verify migrations, then switch the API domain. Do not enable billing/writes on two independent databases. A stable API domain can avoid an APK change; a direct `onrender.com` app configuration requires an app update to the AWS URL. Follow [AWS_ARCHITECTURE.md](AWS_ARCHITECTURE.md) for capacity and load-test requirements.
