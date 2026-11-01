# Move the small launch from Render to AWS EC2

This deploys Django, its generation worker, and a Caddy HTTPS edge on one EC2 instance. It keeps the **existing Aiven PostgreSQL and Valkey** and the existing private S3 bucket. No data is copied into a second database during this first cutover. It is a small-launch setup, not the architecture for one million concurrent users; use [AWS_ARCHITECTURE.md](AWS_ARCHITECTURE.md) for that later stage.

AWS Free Tier eligibility depends on the account creation date and remaining credits. For new accounts the Free plan ends after six months or when credits run out; existing-account offers differ. Check the AWS Billing **Credits** and **Free Tier** pages before creating resources. EC2, public IPv4, S3 storage/requests/egress, optional paid domain/DNS, and any later RDS/ElastiCache services can consume credits or incur charges. Create a small AWS Budget with email alerts. fal.ai, Aiven and Google Play charges are separate from AWS credits.

## 1. Prepare AWS

1. Pick one region, preferably the region of the existing S3 bucket (`MOTION_AI_AWS_REGION`). In EC2, choose a Free Tier eligible Amazon Linux 2023 instance. A `t3.small` has more headroom for the API plus worker than a 1 GiB micro, but only choose it if your account's Free Tier page marks it eligible and your credit budget permits it. Give the instance adequate EBS space for Docker images and logs.
2. Add a security group with inbound TCP 80 and 443 from the internet. Restrict SSH 22 to your IP, or use Session Manager. **Do not expose 8000, PostgreSQL or Valkey**. Allocate one Elastic IP address and associate it with the instance so the public IPv4 address stays stable. Check its public IPv4 charges in AWS Billing. This Elastic IP is the app's public API address.
3. Attach an EC2 IAM role with only the needed private bucket permissions: `s3:GetObject`, `s3:PutObject`, `s3:DeleteObject`, and `s3:AbortMultipartUpload` on that bucket's objects. Keep S3 Block Public Access on. Set instance metadata to IMDSv2 required with response hop limit **2**, so boto3 inside the containers can use the role. Leave `MOTION_AI_AWS_ACCESS_KEY_ID`, `MOTION_AI_AWS_SECRET_ACCESS_KEY` and `MOTION_AI_AWS_SESSION_TOKEN` empty when using the role.
4. Install Docker Engine, Docker Compose v2, and Git on the instance. Verify `docker compose version`. Keep Docker running after reboot.

### HTTPS directly on the Elastic IP

Use the bare Elastic IP; no DNS name is needed. The supplied Caddy 2.11 configuration explicitly requests a **publicly trusted IP address certificate** from Let's Encrypt with the `shortlived` ACME profile. This certificate lasts about six days. Caddy stores its account and certificates on the persistent `caddy-data` volume and renews automatically. Keep ports 80/443 reachable and monitor the certificate expiry and Caddy logs; do not publish the IP-based Android build until `curl -fsS https://YOUR_ELASTIC_IP/ready` succeeds without `-k` or certificate bypass. A plain HTTP address or a self-signed certificate is not suitable for the production app or authenticated Play notifications.

## 2. Prepare the private configuration

Clone the repository and work in `backend-django/`. Create `backend-django/.env` privately from the Render environment settings or `.env.example`. Preserve the **same** Django secret, JWT secret, Play billing encryption key, owner name, Google OAuth client ID, fal key, provider rates, wallet settings, and database and Valkey URLs. Preserve the Aiven `?sslmode=require` and `rediss://` forms. Set the values below for AWS; do not commit `.env` or service-account files.

```ini
MOTION_AI_API_HOST=YOUR_ELASTIC_IP
MOTION_AI_ALLOWED_HOSTS=YOUR_ELASTIC_IP
MOTION_AI_CSRF_TRUSTED_ORIGINS=https://YOUR_ELASTIC_IP
MOTION_AI_CURRENT_SITE_URL=https://YOUR_ELASTIC_IP
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

`MOTION_AI_PROXY_MODE=alb` enables the existing trusted-edge logic for Caddy too. Only Caddy publishes a host port; Django is confined to the Docker network. Copy the existing Google Play service-account JSON to `backend-django/secrets/google-play.json` on EC2 and restrict its file permissions. The compose file mounts it read-only into the setup, API, and worker containers. Keep `MOTION_AI_PLAY_ENABLED`, subscriptions, top-ups and `MOTION_AI_AUTH_ENABLED` at their current working values.

## 3. Start and verify before cutover

From `backend-django/`, run:

```sh
docker compose -f compose.aws.yaml config --quiet
docker compose -f compose.aws.yaml up -d --build
docker compose -f compose.aws.yaml ps
curl -fsS https://YOUR_ELASTIC_IP/ready
curl -fsS https://YOUR_ELASTIC_IP/auth/motion-ai/config
```

The one-shot `setup` service runs migrations, rate synchronization, and optional owner bootstrap **before** the API and worker start. If it fails, inspect `docker compose -f compose.aws.yaml logs setup`; do not point users at AWS yet. Caddy must obtain a valid Let's Encrypt certificate for the IP; if HTTPS fails, inspect `docker compose -f compose.aws.yaml logs web` before changing Play or Android. Check API and worker logs for database, Valkey, S3 and fal connectivity without printing secrets. Test sign-in, one low-cost image job, `/api/v1/jobs`, wallet and owner access. Keep the Render service operating during these checks.

## 4. Move the Play callback and Android app

1. In Google Cloud Pub/Sub, change the existing push subscription endpoint to `https://YOUR_ELASTIC_IP/api/v1/billing/play-notifications`. Clear any old Render value in `MOTION_AI_PLAY_PUBSUB_AUDIENCE`, so Django derives the new callback URL from `MOTION_AI_CURRENT_SITE_URL`. Set the Pub/Sub OIDC audience to that **exact** HTTPS callback URL. Keep the same service account in `MOTION_AI_PLAY_PUBSUB_SERVICE_ACCOUNT`. Send a test notification and expect HTTP 204 in the AWS API logs. If Pub/Sub rejects an IP literal as its endpoint, use a DNS name or a managed HTTPS front door; do not bypass certificate validation.
2. Build the Android bundle from the repository root with the AWS URL:

   ```powershell
   powershell -ExecutionPolicy Bypass -File .\scripts\build-android.ps1 -PmotionAiApiBaseUrl=https://YOUR_ELASTIC_IP bundleRelease
   ```

   The current APKs are compiled with the Render address; they cannot switch to AWS by changing a server environment variable. Publish this new bundle to Play and verify a tester upgrades, signs in, purchases and creates a job before retiring Render.
3. Keep the original PostgreSQL database as the single source of truth throughout. At final cutover, stop old Render API writes and the old generation worker, then let AWS alone process jobs and purchases. Never run two workers against separate copies of the financial database. Keep Render available long enough for previously installed app versions, or accept that those versions will stop working when Render is removed.

For later expansion, move PostgreSQL, Valkey, API and workers into the managed AWS architecture in [AWS_ARCHITECTURE.md](AWS_ARCHITECTURE.md). This one-instance stack has no high availability and must not be presented as capable of one million concurrent users.
