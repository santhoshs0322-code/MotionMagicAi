# Deploy the small launch on AWS with motionmagicai.in

This deploys Django, its generation worker, and a Caddy HTTPS edge on one EC2 instance at **https://api.motionmagicai.in**. It keeps the **existing Aiven PostgreSQL and Valkey** and the existing private S3 bucket. No data is copied into a second database during this cutover. The main and `www` domains temporarily redirect to the existing GamixTV website, preserving paths such as `/terms` and `/privacy`. It is a small-launch setup, not the architecture for one million concurrent users; use [AWS_ARCHITECTURE.md](AWS_ARCHITECTURE.md) for that later stage.

## Owner-managed home showcase

After deploying the updated backend and Android app, open `https://api.motionmagicai.in/owner/showcase/` with the existing owner login. Add a template title, category, prompt, mode, enabled model rate, supported aspect ratio and duration. Upload a poster (JPG/PNG/WebP), a short MP4 sample video, and optionally an example reference image. Publish to show it in the Android home feed; mark one item Featured for the large banner. The example reference image explains the template; each user still selects their own input before generation. You can edit or unpublish templates without rebuilding Android.

The upload form sends the owner's media through Django to the private S3 bucket. Keep sample MP4 files short and compressed; the app initially downloads posters only, then plays one video when its preview opens. The backend caches the public metadata feed in Valkey for 60 seconds and clears it on edits. At larger traffic, serve posters and preview videos through CloudFront and move owner uploads to direct S3 upload so the EC2 web worker does not handle media bytes.

AWS Free Tier eligibility depends on the account creation date and remaining credits. For new accounts the Free plan ends after six months or when credits run out; existing-account offers differ. Check the AWS Billing **Credits** and **Free Tier** pages before creating resources. EC2, public IPv4, S3 storage/requests/egress, optional paid domain/DNS, and any later RDS/ElastiCache services can consume credits or incur charges. Create a small AWS Budget with email alerts. fal.ai, Aiven and Google Play charges are separate from AWS credits.

## 1. Prepare AWS

1. Pick one region, preferably the region of the existing S3 bucket (`MOTION_AI_AWS_REGION`). In EC2, choose a Free Tier eligible Amazon Linux 2023 instance. A `t3.small` has more headroom for the API plus worker than a 1 GiB micro, but only choose it if your account's Free Tier page marks it eligible and your credit budget permits it. Give the instance adequate EBS space for Docker images and logs.
2. Add a security group with inbound TCP 80 and 443 from the internet. Restrict SSH 22 to your IP, or use Session Manager. **Do not expose 8000, PostgreSQL or Valkey**. Reuse an available Elastic IP, or allocate one in the instance's region, then associate it with the instance. Check its public IPv4 charges in AWS Billing. A normal automatically assigned public address changes after stop/start; it is not an Elastic IP. The Android app uses the domain, while DNS points to this associated Elastic IP.
3. Attach an EC2 IAM role with only the needed private bucket permissions: `s3:GetObject`, `s3:PutObject`, `s3:DeleteObject`, and `s3:AbortMultipartUpload` on that bucket's objects. Keep S3 Block Public Access on. Set instance metadata to IMDSv2 required with response hop limit **2**, so boto3 inside the containers can use the role. Leave `MOTION_AI_AWS_ACCESS_KEY_ID`, `MOTION_AI_AWS_SECRET_ACCESS_KEY` and `MOTION_AI_AWS_SESSION_TOKEN` empty when using the role.
4. Install Docker Engine, Docker Compose v2, and Git on the instance. Verify `docker compose version`. Keep Docker running after reboot.

### GoDaddy DNS and domain HTTPS

If the EC2 deployment already exists, keep it and its database, storage, role and credentials. Finish the Elastic IP association and DNS steps before switching Android or Play callbacks.

In GoDaddy Domain Portfolio, select `motionmagicai.in`, then **DNS > DNS Records**. When using GoDaddy nameservers, add or edit these records; leave unrelated records such as email configuration in place:

| Type | Name | Value | TTL |
| --- | --- | --- | --- |
| A | `api` | Your associated Elastic IP | 1 hour |
| A | `@` | Your associated Elastic IP | 1 hour |
| CNAME | `www` | `@` | 1 hour |

Do not use an old released public address or the instance's private `172.31.*` address. In Windows PowerShell, run `nslookup api.motionmagicai.in`, `nslookup motionmagicai.in` and `nslookup www.motionmagicai.in` and confirm the results point to the associated Elastic IP. GoDaddy changes can take up to 48 hours to propagate globally.

The supplied `Caddyfile.aws` requests and renews standard domain HTTPS certificates automatically. It proxies the configured API hostname to Django. The main domain serves the companion browser studio; `www` redirects to the main domain, while Terms/Privacy paths retain their Gamixtv redirects. Configure the web origin using [WEB_SETUP.md](WEB_SETUP.md). Caddy keeps its account and certificates on the persistent `caddy-data` volume. Keep ports 80/443 reachable; do not publish the domain build until HTTPS readiness succeeds without `-k` or another certificate bypass. There is no bare-IP certificate or IP fallback in this configuration.

References: [AWS Elastic IP association](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/working-with-eips.html), [GoDaddy A records](https://www.godaddy.com/en-in/help/add-an-a-record-19238), [GoDaddy CNAME records](https://www.godaddy.com/en-in/help/add-a-cname-record-19236), [Caddy HTTPS](https://caddyserver.com/docs/automatic-https).

## 2. Prepare the private configuration

Clone the repository and work in `backend-django/`. Create `backend-django/.env` privately from the Render environment settings or `.env.example`. Preserve the **same** Django secret, JWT secret, Play billing encryption key, owner name, Google OAuth client ID, fal key, provider rates, wallet settings, and database and Valkey URLs. Preserve the Aiven `?sslmode=require` and `rediss://` forms. Set the values below for AWS; do not commit `.env` or service-account files.

```ini
MOTION_AI_API_HOST=api.motionmagicai.in
MOTION_AI_ALLOWED_HOSTS=api.motionmagicai.in
MOTION_AI_CSRF_TRUSTED_ORIGINS=https://api.motionmagicai.in
MOTION_AI_CURRENT_SITE_URL=https://api.motionmagicai.in
MOTION_AI_PLAY_PUBSUB_AUDIENCE=https://api.motionmagicai.in/api/v1/billing/play-notifications
MOTION_AI_PROXY_MODE=alb
MOTION_AI_BEHIND_ALB=false
MOTION_AI_SSL_REDIRECT=true
MOTION_AI_WEB_WORKERS=1
MOTION_AI_WEB_THREADS=2
MOTION_AI_AWS_REGION=ap-south-1
MOTION_AI_S3_BUCKET=YOUR_EXISTING_PRIVATE_BUCKET
MOTION_AI_AWS_ACCESS_KEY_ID=
MOTION_AI_AWS_SECRET_ACCESS_KEY=
MOTION_AI_AWS_SESSION_TOKEN=
MOTION_AI_DATABASE_URL=YOUR_EXISTING_POSTGRES_URI
MOTION_AI_REDIS_URL=YOUR_EXISTING_VALKEY_URI
MOTION_AI_PLAY_CREDENTIALS_FILE=/run/secrets/google-play.json
```

For an existing deployment, edit only the public-host and proxy entries above and preserve all other working `.env` values. `MOTION_AI_API_HOST` is a hostname without a scheme or path; `CURRENT_SITE_URL` and the trusted origins are HTTPS URLs. An empty `MOTION_AI_PLAY_PUBSUB_AUDIENCE` also works because Django derives it from `CURRENT_SITE_URL`; replace any explicit old-IP or Render audience instead of leaving it active.

`MOTION_AI_PROXY_MODE=alb` enables the existing trusted-edge logic for Caddy too; it does not require a separate AWS load balancer in this compose setup. Only Caddy publishes a host port; Django is confined to the Docker network. Copy the existing Google Play service-account JSON to `backend-django/secrets/google-play.json` on EC2 and restrict its file permissions. The compose file mounts it read-only into the setup, API, and worker containers. Keep the working authentication and credit-purchase switches at their existing values.

## 3. Start and verify before cutover

From `backend-django/`, run:

```sh
docker compose -f compose.aws.yaml config --quiet
docker compose -f compose.aws.yaml up -d --build --force-recreate
docker compose -f compose.aws.yaml ps
curl -fsS https://api.motionmagicai.in/ready
curl -fsS https://api.motionmagicai.in/auth/motion-ai/config
curl -I https://motionmagicai.in/terms
```

The one-shot `setup` service runs migrations, rate synchronization, and optional owner bootstrap **before** the API and worker start. Recreating the containers also ensures Caddy picks up the updated bind-mounted file and hostname. If setup fails, inspect `docker compose -f compose.aws.yaml logs setup`. If HTTPS fails, inspect `docker compose -f compose.aws.yaml logs --tail=50 web` and verify DNS and ports before changing Play or Android. Readiness should return `{"ok":true}`; the public Terms request should return HTTP 302 to `https://gamixtv.in/terms`. In Windows PowerShell, use `curl.exe` rather than the `curl` alias. Check sign-in, saved projects, wallet and owner access without printing secrets. These readiness and configuration requests do not submit paid AI generations.

## 4. Move the Play callback and Android app

1. After domain HTTPS is working, edit the existing Google Cloud Pub/Sub push subscription `motionmagic-play-rtdn-push`. Set its endpoint and OIDC audience to `https://api.motionmagicai.in/api/v1/billing/play-notifications`. Keep authentication enabled, the same topic and the same service account configured in `MOTION_AI_PLAY_PUBSUB_SERVICE_ACCOUNT`. The audience must match the backend exactly, including the path and absence of a trailing slash. Coordinate the backend audience update with this change; mismatched notifications are rejected and retried. Send a Play Console test notification and expect HTTP 204 in the AWS API logs. Native Google sign-in keeps the existing OAuth client IDs, Android package and signing fingerprints; this API-host change does not introduce a browser OAuth redirect.
2. Build the Android bundle from the repository root with the AWS URL:

   ```powershell
   powershell -ExecutionPolicy Bypass -File .\scripts\build-android.ps1 -PmotionAiApiBaseUrl=https://api.motionmagicai.in bundleRelease
   ```

   `gradle.properties` and the Gradle fallback now both use the stable domain, including builds made through Android Studio. Existing APKs retain their old compiled address and require a new signed Play bundle. A released old EC2 public IP cannot be kept serving those APKs. Saved sign-in credentials are bound to the API origin, so the domain build may request one fresh Google sign-in. Signing into the original account reconnects its existing credits and projects; the database and signing secrets remain unchanged.
3. Keep the original PostgreSQL database as the single source of truth throughout. If Render still runs, retire its API writes and old worker after testing the replacement. Never run workers against separate copies of the financial database. For future EC2 moves, associate the Elastic IP with the replacement instance, or update the domain's A records after preparing the new host; domain-based Android builds keep the same API URL.

For later expansion, move PostgreSQL, Valkey, API and workers into the managed AWS architecture in [AWS_ARCHITECTURE.md](AWS_ARCHITECTURE.md). This one-instance stack has no high availability and must not be presented as capable of one million concurrent users.
