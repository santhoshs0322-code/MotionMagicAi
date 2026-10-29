# Render first, AWS later

Deploy the dedicated Django backend from this repository. The root [`render.yaml`](../render.yaml) connects a Docker web service, PostgreSQL 17 and a Redis-compatible Render Key Value cache in Singapore. Application settings retain the `MOTION_AI_` prefix. The same container can move to AWS later.

This is an initial staging setup, not a capacity claim for one million concurrent users. The Blueprint selects **paid web and PostgreSQL compute** and a free, volatile cache. Render shows the cost before you apply it. Account data, wallets, ledger entries and the job outbox live in PostgreSQL; cache loss does not delete those records. Upgrade the cache and load-test before public production traffic. See [Render's Blueprint reference](https://render.com/docs/blueprint-spec) and [Key Value documentation](https://render.com/docs/key-value).

## Deploy from GitHub

1. Push the backend and root `render.yaml` to the repository's `dev` branch.
2. In Render, choose **New → Blueprint**, connect `santhoshs0322-code/MotionMagicAi`, select **dev**, and use `render.yaml` at the repository root.
3. Review the resources and apply. Render generates two different secrets, injects the private PostgreSQL/cache URLs, builds the container and runs `python manage.py migrate --noinput` before starting the web service.
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
| Pre-deploy command | `python manage.py migrate --noinput` |
| Health check | `/ready` |
| Region | Same region as PostgreSQL and Key Value |

Do not attach this service to CandyCraze's database or reuse its secrets. Existing Node sessions do not authenticate against MotionMagic Django.

## Environment settings

The Blueprint supplies the following. Do not copy actual secret values into Git or the APK.

| Setting | Source / initial value |
|---|---|
| `MOTION_AI_DJANGO_SECRET` | Render-generated random secret |
| `MOTION_AI_JWT_SECRET` | A separately generated random secret |
| `MOTION_AI_DATABASE_URL` | Render PostgreSQL internal connection string |
| `MOTION_AI_REDIS_URL` | Key Value internal connection string |
| `MOTION_AI_PROXY_MODE` | `render` |
| `MOTION_AI_DEBUG`, `MOTION_AI_TESTING` | `false` |
| `MOTION_AI_SSL_REDIRECT` | `true` |
| `MOTION_AI_WEB_WORKERS`, `MOTION_AI_WEB_THREADS` | `1`, `4` for the initial small instance |
| `MOTION_AI_AUTH_ENABLED` | `false` until Google OAuth is ready |
| `MOTION_AI_MARKUP_BPS` | `15000` (150% markup, cost × 2.5 before fee gross-up) |
| `MOTION_AI_STORE_FEE_BPS` | `0`; set the actual applicable fee before billing |
| `MOTION_AI_DAILY_PROVIDER_BUDGET_PAISE` | `0` |
| `MOTION_AI_PLAY_ENABLED` | `false` |

`PORT` and `RENDER_EXTERNAL_HOSTNAME` are platform-owned variables automatically supplied by Render, not new application settings. Gunicorn reads `PORT` and defaults to 8000 when it is absent. For a custom domain, additionally set `MOTION_AI_ALLOWED_HOSTS=api.your-domain.com` and `MOTION_AI_CSRF_TRUSTED_ORIGINS=https://api.your-domain.com`; the exact Render hostname remains allowed.

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

Create the Google Web OAuth client and Android OAuth client in the same Google Cloud project. Register Android package `com.motionmagic.ai` and the SHA-1 certificate for the build you will install. The debug certificate is different from Play App Signing; both need their own registration when used. Obtain local signing information with `scripts/build-android.ps1 signingReport` from the repository root.

Add `MOTION_AI_GOOGLE_CLIENT_ID=<your Web client ID>.apps.googleusercontent.com` in the Render web service, then enable `MOTION_AI_AUTH_ENABLED=true`. When retaining this setting, also change its Blueprint value to `"true"` before the next Blueprint sync; declarative values are reapplied during sync. The client ID is public configuration, not the Google client secret. No client secret is required for this native ID-token flow.

Build Android from the repository root with the deployed address:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build-android.ps1 -PmotionAiApiBaseUrl=https://YOUR-ACTUAL-SERVICE.onrender.com assembleDebug
```

Confirm `/auth/motion-ai/config` reports enabled and returns the correct Web client ID. Then test a real sign-in, `/me`, logout/revocation and sign-in again from the installed app. No provider keys or production purchases are needed to verify authentication.

## Enable generation later

The initial Blueprint intentionally has no generation worker: the current worker refuses to run without a configured provider gateway. Once the gateway, private S3 storage, budget and server rate rows are verified, create a separate Render Background Worker from the same `dev` branch/root/Dockerfile with Docker command `python manage.py run_generation_worker`. Use the same database, cache, JWT/Django secrets and provider/storage configuration as the API. Deploy migrations through the API first; never run a worker inside the web process. Keep file uploads in private object storage, not the web container's ephemeral filesystem.

Run `python manage.py cleanup_expired` periodically from a scheduled job or Render Shell. Configure `reconcile_purchases` only after real Google Play verification is enabled. Subscriptions and provider adapters remain separate integration work.

## Move to AWS after validation

Use the same Docker image with ECS/Fargate, PostgreSQL-compatible RDS/Aurora, a shared Redis-compatible cache and a private S3 bucket. Set `MOTION_AI_PROXY_MODE=alb`, exact AWS/custom hosts and the AWS database/cache URLs. Keep signing/encryption secrets consistent if existing sessions and encrypted records must remain usable.

Before cutover, freeze writes and workers, back up and restore PostgreSQL, preserve account IDs and all financial tables, verify migrations, then switch the API domain. Do not enable billing/writes on two independent databases. A stable API domain can avoid an APK change; a direct `onrender.com` app configuration requires an app update to the AWS URL. Follow [AWS_ARCHITECTURE.md](AWS_ARCHITECTURE.md) for capacity and load-test requirements.
