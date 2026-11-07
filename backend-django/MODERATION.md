# Moderated generation failures

Confirmed fal `content_policy_violation` errors are stored as that stable error code on the failed generation. Detection reads typed error envelopes, including `detail` arrays, rather than matching prompt text or human-readable messages. Safety-flagged output also uses this code. Ordinary validation and infrastructure failures retain their existing classifications.

Both submission and queued-result rejection are terminal. The worker releases the user's reserved credits once, does not resubmit the request, and never downloads or stores flagged output. Provider cost allowances retain the existing conservative accounting rules; they are not fal invoice amounts. Safety checking remains enabled.

Android shows a blurred source preview and centered block icon in both the project grid and its detail sheet, with a clear reason and credit-release message. There is no download action for failed jobs. When no source preview is available, the same blocked state uses the colored placeholder.

Before discarding a failed job's temporary upload preview, the app saves only a tiny, already-obscured JPEG with a longest edge of 12 pixels. This private cache is scoped to API origin and account, uses hashed job filenames, and retains at most 128 previews per account. It lets the blocked card remain recognizable as a blocked item after refresh or restart without retaining the full temporary upload. Android versions without render-effect blur still receive the low-detail obscured image.

Home's Continue Story card opens `Screen.STORY` directly. Only the explicit **Use a saved video** action opens Projects. Story settings offer server-enabled reference/edit models; Image to Video settings offer standard video models.

Deploy both API and worker code, then rebuild/distribute Android:

```bash
cd ~/MotionMagicAi
git pull origin dev
cd backend-django
docker compose -f compose.aws.yaml up -d --build
```

No environment changes or new database migration are required. Existing generic failed jobs cannot be retroactively identified as moderation failures without authoritative error information; terminal jobs are not polled again to guess their reason.
