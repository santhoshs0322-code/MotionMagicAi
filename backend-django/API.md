# API contract

All JSON responses include `ok`. Protected routes require `Authorization: Bearer <JWT>`. Mutation bodies reject unknown fields. Create-job requests also require a stable `Idempotency-Key`; retry with the same key after timeouts.

| Method and path | Purpose |
|---|---|
| `GET /health` | Process liveness; no database dependency |
| `GET /ready` | Database and shared Redis readiness |
| `GET /auth/motion-ai/config` | Public Google Web client ID |
| `POST /auth/motion-ai/google/native` | Verify a Google ID token and issue a MotionMagic JWT |
| `GET /auth/motion-ai/me` | Verify session and return account |
| `POST /auth/motion-ai/logout` | Revoke current JWT |
| `GET /api/v1/capabilities` | Actual local/cloud services; cloud entries are configuration gated |
| `GET /api/v1/wallet` | Balance, held and available integer paise; includes Play account-binding hash |
| `GET /api/v1/transactions` | Cursor-paginated immutable ledger |
| `POST /api/v1/quotes` | Server-priced generation quote |
| `GET, POST /api/v1/jobs` | Cursor-paginated jobs; reserve a quoted job |
| `GET, DELETE /api/v1/jobs/{id}` | Poll or cancel a still-queued job |
| `GET, POST /api/v1/assets` | List media; request an exact-size, five-minute S3 upload form |
| `GET, POST, DELETE /api/v1/assets/{id}` | Download, confirm upload, or delete owned media |
| `GET /api/v1/billing/products` | Verified enabled one-time packs only |
| `POST /api/v1/billing/verify` | Verify and grant one Google Play credit purchase |
| `GET /api/v1/billing/purchases` | Cursor-paginated purchase history |

Google Nano Banana image models and Gemini Omni video use server-only `MOTION_AI_GEMINI_API_KEY`. Qwen, GPT Image, Seedream, original Nano Banana, Kling O3 and PixVerse V6 use `MOTION_AI_FAL_KEY`. The fal adapter submits once to its queue, saves the request ID, polls status and streams results into private S3. The optional private gateway remains available for other services. See [PROVIDERS.md](PROVIDERS.md).

Capabilities include `config.app_model_id`, supported ratios/durations, resolution/image size, audio-priced variant and `minimum_charge_paise` / `minimum_units`. They do not include secrets or provider URLs. Quote POST accepts `service`, `prompt`, optional owned ready `asset_id`, `aspect_ratio`, video `duration` and `generated_audio`. It returns a five-minute quote ID, title and maximum charge; the server decides price. A fal silent rate rejects a sound-on request. One request generates one output. Job GET includes kind/title/aspect/duration for download recovery. Asset GET returns a short-lived S3 URL plus MIME/size metadata; stream the file and verify its size.

`config.image_aspect_ratio=source` means an image-to-video result inherits the uploaded photo's shape; ratio controls apply to text-to-video for these providers. Synchronous submission timeouts never trigger another paid provider POST. Retry the app's job POST with the same quote and idempotency key to recover safely.

Upload forms require an exact content length and allowed MIME type. S3 CORS should allow only the required `POST` from the Android networking flow; the bucket remains private, blocks public access and uses lifecycle rules. A later antivirus/content-validation worker should inspect uploads before adding formats beyond the current allowlist.
