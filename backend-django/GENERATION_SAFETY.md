# Server-enforced generation safety

The request builder in `studio/fal_catalog.py` fixes the supported safety settings
before sending any paid request. Neither request payloads nor saved rate configuration
can switch them off. These are backend controls, with no customer toggle or extra
network request. Existing model availability and credit pricing are unchanged.

Verified against fal's public OpenAPI schemas for all 24 configured text, image-edit,
image-to-video and video-reference endpoints on 6 November 2026:

| Models | Enforced setting |
| --- | --- |
| Qwen Image, GPT Image 2 / 2.5, Seedream 4.5 / 5, Kling O3 (including Story Reference and Video Edit), PixVerse V6 | `enable_safety_checker=true` |
| Nano Banana text and edit | `safety_tolerance="1"` (strictest supported value) |

These Nano Banana endpoints expose tolerance instead of an `enable_safety_checker`
parameter. The other configured endpoints do not expose `safety_tolerance`. We send
only the documented control. Nano Banana remains excluded from the launch catalogue;
this change does not enable it.

Safety checking was already enabled on the active models. The previous Nano Banana
tolerance was `"4"`; the centralized policy now uses `"1"`. Controls apply when a worker
submits a new request; an already-submitted provider request cannot be changed.

The existing result handler refuses Qwen results flagged by `has_nsfw_concepts` before
downloading or storing them. Provider failures and blocked results finish as failed,
releasing reserved user credits without recording a generation charge.
A provider may still incur cost: a released customer hold is not proof of a free
provider request. Provider safety checking does not guarantee every output is suitable.

Verification uses mocked provider requests, tests every configured variant and both
input paths, tests attempted overrides, and checks that flagged output is not downloaded,
stored or charged. Fetching the public schemas does not submit generations.

Deploy the backend, including its worker, to apply the policy:

```bash
cd ~/MotionMagicAi
git pull origin dev
cd backend-django
docker compose -f compose.aws.yaml up -d --build
```

No rate synchronization, Android rebuild, environment changes or migrations are needed.

Sources:
- https://fal.ai/models/fal-ai/nano-banana/api
- https://fal.ai/models/fal-ai/nano-banana/edit/api
- https://fal.ai/models/fal-ai/qwen-image/api
- https://fal.ai/models/fal-ai/kling-video/o3/pro/text-to-video/api
- https://fal.ai/api/openapi/queue/openapi.json?endpoint_id=fal-ai%2Fpixverse%2Fv6%2Ftext-to-video
