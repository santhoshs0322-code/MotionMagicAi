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

Google Nano Banana image models and Gemini Omni video use the server-only `MOTION_AI_GEMINI_API_KEY`. Image results are uploaded to private S3; Omni video uses provider URI delivery and streams the download into S3 with bounded memory. Other providers still go only through `MOTION_AI_GATEWAY_URL`, whose contract must support idempotent submit by MotionMagic job UUID and authoritative lookup by that UUID. A service appears in capabilities only when its adapter, storage, positive cost row and daily provider budget are configured.

Upload forms require an exact content length and allowed MIME type. S3 CORS should allow only the required `POST` from the Android networking flow; the bucket remains private, blocks public access and uses lifecycle rules. A later antivirus/content-validation worker should inspect uploads before adding formats beyond the current allowlist.
