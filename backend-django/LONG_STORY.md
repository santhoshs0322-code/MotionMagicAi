# Long Story (1–5 minutes)

Lengths use 30-second increments: 1, 1.5, 2, 2.5, 3, 3.5, 4, 4.5 and 5 minutes.
Each scene remains 15 seconds; half-minute lengths use 6, 10, 14 or 18 scenes.
The Android slider updates the local catalogue estimate immediately. Create still requests an authoritative quote.
Deploy the backend and run `python manage.py sync_provider_rates` to publish these lengths, then rebuild Android.

Home → Long Story, or Continue Story → Create a 1–5 minute story.
One required prompt, optional one or two character photos and/or a 3–15 second MP4 character reference,
1080p, 9:16 / 16:9 / 1:1, and 1 / 2 / 3 / 4 / 5 minutes. Character dialogue and
lip sync use Kling's native audio. This is not voice cloning or a guarantee of
identical voices/faces across independently generated scenes. A character video
guides identity; it does not copy the reference's exact motion or soundtrack.

## Server configuration

Provider keys stay on the server:

- OpenAI Responses, default `gpt-5.6-terra`: background structured screenplay.
- `fal-ai/kling-video/o3/pro/reference-to-video`: 15-second native-audio scenes,
  with fixed character elements reused on every scene. Safety checking is mandatory.
- Prompt-only stories use `fal-ai/kling-video/o3/pro/text-to-video`, without reference
  fields. Supported inputs: prompt only, one photo, two photos, video only, and
  one or two photos together with a video. All include the user's story prompt.

Add these to the private backend `.env` on both API and worker:

```ini
MOTION_AI_OPENAI_API_KEY=YOUR_OPENAI_API_KEY
MOTION_AI_OPENAI_STORY_MODEL=gpt-5.6-terra
MOTION_AI_LONG_STORY_COST_PAISE_PER_SECOND=14
MOTION_AI_LONG_STORY_PLAN_COST_PAISE=20
```

These are **provider allowances in integer USD cents**, despite the legacy PAISE
names. Settings default to zero (disabled) until explicitly configured.
fal currently lists native-audio O3 Pro reference-to-video at $0.14/output second.
The per-second allowance must cover O3 Pro reference generation with native audio
and the optional input-video element. The planning allowance must cover one
OpenAI call with up to 12,000 output tokens, system/schema instructions and a
1,000-character user brief. `20` cents is a conservative planning allowance for
the default model at the reviewed $2/M input and $12/M output rates. Recheck its
price before changing the model. The video allowance is separate and must be
verified for the exact fal endpoint before launch or after a provider price change.
Do not copy the ordinary Kling image-to-video price without checking this endpoint.
Round fractional cents upward. The daily provider budget must cover the whole
film **within the user's budget shard**. Do not enable this on an underfunded account.

For `S` seconds, configured per-second cost `C`, plan allowance `P`, overhead `H`:

```
provider reservation = S × C + P
customer credits = existing services.price(S × C + P + H)
```

Capabilities expose the same complete price for all five durations, so local
estimates and authoritative quotes agree. Existing margin/Play-fee settings apply.
The dollar-to-credit convention remains 200 credits per dollar; this feature
does not change the global pricing formula.

Example maximum quotes with the above allowances, no extra overhead, 16,667 markup
basis points and a 15% assumed Play fee:

| Minutes | Scenes | Provider allowance | Customer credits |
| --- | --- | --- | --- |
| 1 | 4 | $8.60 | 2,699 |
| 2 | 8 | $17.00 | 5,334 |
| 3 | 12 | $25.40 | 7,969 |
| 4 | 16 | $33.80 | 10,605 |
| 5 | 20 | $42.20 | 13,240 |

These include a $0.20 planning allowance, not a claim of actual token usage or
the store's cash price for a credit pack. Different deployed fee/markup settings
change these quotes.

Deploy/rebuild:

```sh
cd ~/MotionMagicAi
git pull origin dev
cd backend-django
nano .env
docker compose -f compose.aws.yaml stop -t 60 worker
docker compose -f compose.aws.yaml up -d --build
docker compose -f compose.aws.yaml logs --tail=60 setup worker
```

Setup automatically runs `sync_provider_rates`. Rebuild the Android app to see
the new screen. Parallel scheduling itself is backend-only: an app already using
Long Story does not need another APK for this change. Migration
`0022_story_work_slots` creates the shared permits and adopts existing in-flight
scene requests. Compose setup applies it automatically before starting workers.
Scene checkpoints still use bounded Job JSON. Existing short-video workflows remain available.

## Durable pipeline and storage

1. Quote validates duration, audio, photo count and server-enabled rate.
2. Confirm binds only this account's uploaded assets, reserves the complete price
   and daily provider allowance, and creates **one** parent generation.
3. Worker requests a structured beginning/middle/ending screenplay, validates the
   exact scene count, then generates 4/8/12/16/20 scenes with **two fal scene jobs
   in flight globally** across all users and worker processes. This is an initial
   shared capacity limit for Long Story, not two permits per user. Ordinary
   standalone image/video generation retains its existing limits.
   Submissions are asynchronous; even one worker can keep two remote jobs running.
   Polling one scene never requires the preceding scene to finish. Completion
   updates a separate checkpoint and releases that scene's shared permit.
4. Each paid POST has a saved intent and each accepted request ID is checkpointed.
   An ambiguous POST is never retried; the film fails and credits are released.
   Known provider IDs resume after a worker restart without repaying for scenes.
   The slot and paid intent commit in one transaction before each paid POST.
5. Each scene is streamed to private account-owned S3 storage. Assembly normalizes
   **one scene per worker turn**, at one encoder thread, and saves its checkpoint
   in `accounts/<user>/video/story-parts/<parent>/`. Final concatenation uses stream
   copy, not a second encode. A separate global permit allows **one assembly task**
   across worker processes. Scene indexes preserve narrative order even when
   scenes finish in reverse order. Local scratch files are cleaned on each turn.
6. The final MP4 is verified and saved to S3/Asset, then the parent completes and
   charges once. A failed film releases the complete user hold, even if the
   provider charged for completed scenes. That provider spend still consumes budget.
   A failure stops further submissions. Already accepted sibling requests keep
   their permits until the worker confirms they have ended; it does not download
   or publish their results. Parallel failure can incur a sibling scene's provider
   cost even though the customer receives a full refund.
7. My Projects reads only account-scoped DB rows, displays scene progress, and
   shows one final film. No provider calls on project browsing.

Allow at least **2 GB free scratch disk** and keep the default **1,200-second
worker lease**. A micro instance is suitable only for a small launch; full-HD
assembly can delay its other worker tasks. Use one worker on that instance;
increase rendering capacity before running multiple heavy stories concurrently.
Generation time depends on fal queue times and scene count; 5 minutes of final
video does not mean 5 minutes of waiting. The phone does not retain all scenes.
Successful quotes and scene counts are unchanged by parallelism; there is no
second planning call or extra generation for the same scene. Short continuation
scenes share the original fixed character references and full screenplay, so
parallel work does not depend on a previous scene's result.

Production permit locking requires PostgreSQL. The row limit is enforced by a
transactional control row, not a process-local semaphore or a Redis TTL that might
expire while fal is still rendering. Worker checkpoint leases prevent concurrent
writes to the same story. CPU commands check their lease and assembly permit
before starting; a crashed assembly permit recovers after its lease plus a
300-second grace period (longer than the maximum 240-second CPU command).

## Recovering an ambiguous paid submission

If a process dies between a paid POST and saving its returned request ID, the
customer's hold is released and that scene is never automatically submitted again.
Its global permit stays quarantined because the server cannot prove whether fal
is still processing it. Known requests on failed stories drain automatically.
Inspect permits with this server-only command:

```sh
docker compose -f compose.aws.yaml exec -T api python manage.py story_slots
```

If a permit remains held, first reconcile the attempt in fal and confirm no
matching request is still running. Then use the displayed slot name and exact
token, which prevents releasing a newer attempt accidentally:

```sh
docker compose -f compose.aws.yaml exec -T api python manage.py story_slots \
  --release scene-1 --token DISPLAYED_TOKEN --confirmed-provider-idle
```

This cannot release permits for active stories and never changes customer credits.
Do not add more workers to work around a quarantined paid request.

Intermediate generated clips are private S3 objects, not additional user projects.
The worker removes recorded scene files, normalized parts and its validated input
copy after settlement, preserving the final output and the user's original uploads.
If S3 cleanup fails, retry it with:

```sh
docker compose -f compose.aws.yaml exec -T api python manage.py cleanup_story_parts
```

This processes at most 100 terminal stories older than one hour. It never removes
active stories or original uploads. Incomplete multipart uploads still need the
bucket's abort-incomplete-upload lifecycle rule.

## Owner accounting

Long Story appears in owner rates/jobs and existing user/date/month reports.
fal billing reconciliation sums **all video scene request IDs** and saves the
video subtotal on the parent. OpenAI token usage is saved separately. A fal bill
does not prove an OpenAI charge: the owner's combined-cost record remains pending
until both provider statements are entered through existing invoice reconciliation.
Partial exports and missing/ambiguous IDs also remain pending. Customer charges
are never silently changed after settlement. Conservative job allowances are
not invoices. Storage, CPU and transfer costs still require expense entries.

## Verification

Automated tests use mocked provider calls; no paid generation is run by tests.
Before enabling customer purchases, perform an explicitly authorized paid
one-minute generation on the deployed worker, verify dialogue/lip sync quality,
exact length, reference consistency, final download, and complete invoice cost.

Provider contracts:
https://developers.openai.com/api/docs/guides/structured-outputs
https://developers.openai.com/api/docs/models/gpt-5.6-terra
https://fal.ai/models/fal-ai/kling-video/o3/pro/reference-to-video/api
