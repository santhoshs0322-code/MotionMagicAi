# Current launch: fal only

Nano Banana and Google generation are excluded from the app and server catalog. Keep `MOTION_AI_FAL_ONLY=true` (the default). Google sign-in is independent and remains supported. Owner-only reporting and billed-cost reconciliation: [OWNER_SETUP.md](OWNER_SETUP.md). Historical Google adapter notes below do not enable those models for this launch.

# Image and video provider setup

Use one fal.ai generation key for the launch models; separate Kling, PixVerse, Qwen, ByteDance and OpenAI keys are not needed for these fal routes. Device Voice uses Android TTS and has no provider key.

| App model | Adapter / actual API model | Exposed sizes |
|---|---|---|
| Qwen Image | fal `fal-ai/qwen-image`, `fal-ai/qwen-image-edit` | 720p, 1080p |
| GPT Image 2.5 Sunburst / Flare | fal `openai/gpt-image-2.5/{sunburst,flare}/{text-to-image,edit}` | 1K, 2K, high quality |
| GPT Image 2 | fal `openai/gpt-image-2`, `/edit` | 1K, 2K, high quality |
| Seedream 5 Pro | fal `bytedance/seedream/v5/pro/{text-to-image,edit}` | 1K, 2K |
| Seedream 5 Lite | fal `bytedance/seedream/v5/lite/{text-to-image,edit}` | 2K |
| Seedream 4.5 | fal `fal-ai/bytedance/seedream/v4.5/{text-to-image,edit}` | 2K |
| Kling O3 (formerly labeled Kling 3 Omni) | fal `fal-ai/kling-video/o3/{standard,pro}/{text-to-video,image-to-video}` | 720p / 1080p, 5s / 10s, native sound on/off |
| PixVerse V6 | fal `fal-ai/pixverse/v6/{text-to-video,image-to-video}` | 720p / 1080p, 5s / 10s / 15s, native sound on/off |

The unsupported “PixVerse V6 Max” branding was removed; saved selections migrate to V6. Every image route supports text or one reference image with a prompt. Native video sound is generated output; uploading audio/voice clones, video references, batches and arbitrary provider settings are not implemented. Gemini's duration and silent-output instructions are prompt requests, not guaranteed API controls; its output can differ. Kling/PixVerse have explicit duration/audio parameters. Their image-to-video shape follows the source image. In image sizes, 1K/2K designate approximate square pixel area; non-square dimensions vary with the ratio.

## Render rollout

1. Deploy the code and all migrations, keeping prices/budget at zero initially. Change an existing manual service's pre-deploy command to `python manage.py migrate --noinput && python manage.py sync_provider_rates && python manage.py bootstrap_owner`.
2. Set `MOTION_AI_FAL_KEY` privately on both API and background worker. Keep S3 private; provide the existing prefixed AWS credentials and bucket/region. No keys belong in the APK or Git. Models also require provider balance and account access.
3. Deploy a Render Background Worker using the same code and `python manage.py run_generation_worker`. The Blueprint does not create this additional paid service. Copy database/cache/signing/storage/provider settings; use the worker host/proxy settings in [RENDER_SETUP.md](RENDER_SETUP.md). Start with one worker for the database's 15-connection limit.
4. Set the appropriate cost variables from [.env.example](.env.example), overhead, fee assumptions and daily provider budget, then run `python manage.py sync_provider_rates` or redeploy. Keep settings consistent on both services. The full environment list uses the `MOTION_AI_` prefix. Zero or omitted costs keep individual variants unavailable. Key changes require rate sync too.
5. Check `/api/v1/capabilities`. Only enabled variants appear in live pricing. In Android, sign in, choose an enabled model, add input, tap Create, and review the quote. A funded server wallet is required. Confirm starts generation; Check cloud generation resumes a saved request or downloads a result. Do not treat the Android purchase UI as complete: Play credit verification exists on the backend, but an Android credit-pack checkout is separate work. No subscription entitlements are implemented.

Minimal new variables (placeholders, not working credentials or prices):

```dotenv
MOTION_AI_FAL_KEY=your_fal_server_key
MOTION_AI_FAL_ONLY=true
MOTION_AI_MARKUP_BPS=15000
MOTION_AI_FAL_OVERHEAD_PAISE=0
MOTION_AI_FAL_QWEN_IMAGE_720P_COST_PAISE=0
MOTION_AI_FAL_KLING_3_OMNI_720P_SILENT_COST_PAISE_PER_SECOND=0
MOTION_AI_FAL_KLING_3_OMNI_720P_AUDIO_COST_PAISE_PER_SECOND=0
MOTION_AI_FAL_PIXVERSE_V6_720P_SILENT_COST_PAISE_PER_SECOND=0
MOTION_AI_FAL_PIXVERSE_V6_720P_AUDIO_COST_PAISE_PER_SECOND=0
MOTION_AI_DAILY_PROVIDER_BUDGET_PAISE=0
```

Set actual nonzero cost allowances from your provider account before testing. Image costs are per one output and must cover the largest supported ratio and text/reference input; GPT routes use `high` quality. Video costs are per second, separately for each resolution and audio setting. Add FX, input-token charges, applicable tax, storage and egress allowances. The app displays the resulting server quote rather than invented credits.

`MARKUP_BPS=15000` applies 150% **markup**, or cost × 2.5 before store-fee gross-up. That is 60% gross margin before other expenses, not a 150% profit margin. Existing pricing uses integer paise. Configured allowances remain separate from actual bills. The owner console can reconcile fal billing events or verified INR invoice costs without changing wallet charges; see OWNER_SETUP.md. Monitor usage and adjust allowances. The daily budget bounds configured allowances, not an independently verified provider bill. It is partitioned into 64 account shards, so a shard may run out before the overall budget. Allow at least 64 times the largest single-job allowance to avoid rejecting every job.

## Recovery and resource bounds

- Paid provider POST is attempted once. A timeout or crash before its ID is saved never causes a blind resubmission; the customer is refunded and provider spend is pessimistically counted. Provider IDs survive worker restarts. Retrying the same app quote/idempotency key cannot create a second job or charge.
- fal queue/status/result failures retain the same request ID for reconciliation. After 24 hours without a usable result the hold is released; the provider could still finish later. No webhook is required. Recovery metadata is scoped to the signed-in account and server on Android.
- Maximum input photo: 10 MiB by default; JSON response: 1 MiB; images: 32 MiB; videos: 512 MiB. S3 transfers use bounded multipart chunks and media URLs are HTTPS allowlisted without redirects or provider credentials. Android uses 64 KiB download buffers and verifies size and free disk space. Image previews decode downsampled bitmaps.
- For a file of N bytes, upload/download is O(N) time; working buffers are bounded independently of N. A model's generation time and provider queue delay are external. S3 lifecycle rules must remove abandoned uploads and abort incomplete multipart uploads; app-level deletion currently removes local projects, not all cloud copies.
- Tests mock provider HTTP and S3. Live paid generations, provider account access, PostgreSQL contention and one-million-concurrent-user throughput are not verified by unit tests. The current Render/Aiven staging configuration does not have that capacity.

## Contract references

Verified against public schemas on 2026-10-30: [fal queue](https://fal.ai/docs/documentation/model-apis/inference/queue), [Qwen](https://fal.ai/models/fal-ai/qwen-image/api), [GPT Image Sunburst](https://fal.ai/models/openai/gpt-image-2.5/sunburst/text-to-image/api), [Seedream 5 Pro](https://fal.ai/models/bytedance/seedream/v5/pro/text-to-image/api), [Kling O3](https://fal.ai/models/fal-ai/kling-video/o3/standard/image-to-video/api), [PixVerse V6](https://fal.ai/models/fal-ai/pixverse/v6/text-to-video/api). Each exact schema is available at `https://fal.ai/api/openapi/queue/openapi.json?endpoint_id=...`; endpoints and allowed input fields are pinned in `studio/fal_catalog.py`.
