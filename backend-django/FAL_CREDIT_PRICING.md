# Credit-only launch pricing

MotionMagic grants **200 credits once** after a verified Google sign-in. Credit
packs are one-time, consumable Google Play products; purchased credits do not
expire. The subscription purchase flow is retained in code for a future launch
and disabled by `MOTION_AI_CREDIT_ONLY_MODE=true`.

fal cost environment variables named `*_COST_PAISE*` contain **USD cents**, not
Indian paise. One USD equals 200 customer credits. With
`MOTION_AI_MARKUP_BPS=16667` and `MOTION_AI_STORE_FEE_BPS=1500`, a quote charges
`ceil(raw_fal_cents × 2.6667 / 0.85)` customer credits. This targets about
**25% contribution margin after an assumed 15% Play service fee**, before
GST, AWS, S3, refunds, foreign exchange and provider price changes. Configure
all nonzero provider costs from the live fal account and update them when fal
changes its prices. A zero-cost model remains unavailable.

| Kling O3 1080p service | fal allowance | User quote for 10 seconds |
|---|---:|---:|
| Silent image/text to video | 12 US cents/second | 377 credits |
| Audio image/text to video | 14 US cents/second | 440 credits |
| Story reference video to video | 17 US cents/second | 534 credits |

The story reference rate uses fal's $0.168/second published price rounded up
to 17 cents. Video references must be MP4, 3–15 seconds, at most 200 MB, and
belong to the signed-in account. Each generation is quoted and reserved before
submission. Story export on the device does not consume AI credits.

## Google Play products

The server knows four credit-pack SKUs. It enables the larger packs only
after their matching flags are set. Create and activate each one-time product
in Play Console before enabling its flag.

| SKU | Grant | Suggested Indian Play price* | Flag |
|---|---:|---:|---|
| `motion_topup_500` | 500 credits | ₹349 | `MOTION_AI_ENABLE_TOPUPS` |
| `motion_pack_2500` | 2,500 credits | ₹1,699 | `MOTION_AI_ENABLE_PACK_2500` |
| `motion_pack_5000` | 5,000 credits | ₹3,399 | `MOTION_AI_ENABLE_PACK_5000` |
| `motion_pack_10000` | 10,000 credits | ₹6,799 | `MOTION_AI_ENABLE_PACK_10000` |

*Suggested prices assume roughly ₹95/USD, a 15% Play fee and 18% tax included
in the customer price. Verify actual Play payouts and the current exchange rate
before launch. The old ₹249 price for 500 credits is too low for the new
25% margin target. The Android app intentionally shows the live Play purchase
sheet price rather than a hard-coded rupee amount.

For AWS, set `MOTION_AI_CREDIT_ONLY_MODE=true`,
`MOTION_AI_WELCOME_CREDITS=200`, and the verified per-model cost variables.
Credit-only mode enforces the 16,667/1,500 pricing settings in code, even if
older AWS environment entries still contain the previous markup.
Set `MOTION_AI_ENABLE_TOPUPS=true` for the existing 500-credit product. Set
`MOTION_AI_ENABLE_PACK_2500=true`, `MOTION_AI_ENABLE_PACK_5000=true`, and
`MOTION_AI_ENABLE_PACK_10000=true` only
after activating those products in Play Console. A six-scene 60-second story
using one 10-second Kling O3 Pro audio clip and five 10-second reference clips
costs about **3,110 customer credits** and **$9.80 in fal fees** at this rate
map. The example $10/day provider budget allows roughly one such story per day;
raise it deliberately as customer demand grows.

Provider references: [Kling O3 Pro image to video](https://fal.ai/models/fal-ai/kling-video/o3/pro/image-to-video),
[Kling O3 Pro reference video](https://fal.ai/models/fal-ai/kling-video/o3/pro/video-to-video/reference).
