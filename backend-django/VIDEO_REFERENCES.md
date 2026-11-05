# Video and photo references

The Android Image to Video screen supports two different Kling O3 workflows. Both use the existing server-only fal key and one request containing an owned MP4 plus zero to four ordered reference photos. The separate, ordinary image-to-video flow still uses a starting frame.

| Model | Result | Length and frame | Audio |
|---|---|---|---|
| Kling O3 Video Edit | Change the subject, appearance or setting of the existing clip | Follows the input clip; 1080p Pro endpoint, input 3–15 seconds | Keep existing audio or mute |
| Kling O3 Story Reference | A new scene guided by the reference video's motion/camera and photo appearance | Choose output 3–15 seconds and ratio; 1080p Pro endpoint | Keep existing audio or mute |

MP4 input: maximum 200 MiB, both dimensions 720–3840 pixels. Photos: PNG/JPEG/WebP, at most 10 MiB each. The Android importer normalizes orientation and downsamples photos one at a time. This integration exposes `image_urls`, not the provider's separate character-element feature. Four photos are four references, not a guaranteed identity replacement or four output videos. Outputs remain probabilistic. Silent video stays silent: neither workflow generates a new soundtrack or cloned voice.

The UI labels references `@Video1`, `@Image1`, `@Image2`, etc. Tap a photo to replace it; remove renumbers the remaining references. Example edit prompt: “Replace the subject in @Video1 with the person shown in @Image1. Use @Image2 for the outfit. Preserve the original camera movement.” For a new scene, describe the new action instead of asking to edit the existing frames.

## Credits

Android reads the total credit prices from enabled server capabilities; changing settings updates the estimate locally. Create obtains an authoritative quote including the selected photo count. Uploads start only after confirmation. Job submission validates all account-owned source assets, their order and count, then reserves credits under the wallet lock. Retrying the saved request is idempotent.

The existing credit-only pricing function remains the single calculator. One USD of customer face value is 200 credits. Despite historical field and environment names ending in `PAISE`, fal provider allowances use whole **USD cents** and wallet/quote amounts use **credits**. They are not INR paise. With credit-only mode the existing fixed factors are:

```
provider_allowance_cents = configured_cents_per_second × billed_seconds
credits = ceil((provider_allowance_cents + configured_overhead_cents) × 26667 / 8500)
```

The factor accounts for the credit denomination, a 25% contribution margin on revenue **after an assumed 15% Play fee**, and rounding. This is not a guaranteed 25% of gross receipts or net profit after tax, FX, hosting, S3, refunds or other costs. Actual local pack prices and fees affect the realized margin. Round once on the total, rather than multiplying a separately rounded one-second customer price.

fal listed Pro Edit at $0.168 per generated second when checked on 2026-11-05. A conservative whole-cent allowance is 17 cents/second, the same for keep-audio/mute. With zero overhead:

| Edit clip length | fal listed cost | Configured allowance | Customer credits | Credit face value in USD |
|---|---:|---:|---:|---:|
| 5 seconds | $0.84 | $0.85 | 267 | $1.335 |
| 10 seconds | $1.68 | $1.70 | 534 | $2.67 |
| 15 seconds | $2.52 | $2.55 | 801 | $4.005 |

An edit uses the source length, conservatively rounded up to whole seconds: 4.21 seconds reserves the 5-second price. The worker independently probes the uploaded bytes before making a paid call. It rejects an input longer than the reserved allowance; a shorter verified input can settle a lower charge using the quote's original fee/markup. Story Reference bills the chosen **output** length; its input length does not change the chosen output price. Photos have no separate surcharge in these configured variants. These costs are configured allowances, not verified fal invoices; owner reconciliation remains separate.

Credits are charged only after a successful result is stored. Invalid input, failed generation or cancellation before submission releases the hold. Existing ambiguous provider submission recovery never sends a second paid POST. Projects reads saved DB status, without probing inputs or polling fal from the list request.

## AWS rollout

Deploy this backend before distributing the new Android build. No new API key or database migration is needed. To enable Pro Edit, add both settings to the server `.env` after confirming the allowance against your fal billing:

```dotenv
MOTION_AI_FAL_KLING_O3_EDIT_1080P_SILENT_COST_PAISE_PER_SECOND=17
MOTION_AI_FAL_KLING_O3_EDIT_1080P_AUDIO_COST_PAISE_PER_SECOND=17
```

Zero or omitted costs keep the new model disabled. Existing fal key, S3 access and positive daily provider budget are still required. Existing Story Reference cost variables stay unchanged; `sync_provider_rates` adds the new multi-photo capability to them.

```bash
cd ~/MotionMagicAi
git pull origin dev
cd backend-django
nano .env
docker compose -f compose.aws.yaml up -d --build
docker compose -f compose.aws.yaml exec -T api python manage.py sync_provider_rates
docker compose -f compose.aws.yaml logs --tail=40 worker
```

The Docker image includes ffprobe via ffmpeg. Video validation and upload use a temporary file, 64 KiB transfer buffers, a transfer deadline, single-thread S3 upload and a 30-second probe timeout. They run in the background worker, not the request path. The exact checked video is snapshotted under `accounts/<account>/video/job-inputs/<job>/source.mp4`, which is never exposed through a customer upload form. An unexpired original upload form cannot swap the video after validation. Configure S3 lifecycle expiry for the **job-inputs stage only** (for example, 7 days, longer than the 24-hour reconciliation window) and abandoned uploads. Do not expire generated customer results with that rule. Allow for up to 200 MiB temporary disk per running reference job when sizing workers; a small EC2 instance is not capacity for a million concurrent generations.

Rebuild Android with the API domain, then distribute the signed bundle through Play. Old apps can still create video-only Story Reference jobs; photo-plus-video controls require the new app.

## Verified provider contracts

- [Pro Edit API](https://fal.ai/models/fal-ai/kling-video/o3/pro/video-to-video/edit/api): `video_url`, `image_urls`, `keep_audio`; no output duration/ratio parameter.
- [Pro Edit pricing](https://fal.ai/models/fal-ai/kling-video/o3/pro/video-to-video/edit): price per generated second; recheck before enabling.
- [Pro Reference API](https://fal.ai/models/fal-ai/kling-video/o3/pro/video-to-video/reference/api): `video_url`, `image_urls`, chosen `duration`/`aspect_ratio`, `keep_audio`.

Tests mock all paid fal calls. This change does not perform a live generation or purchase.
