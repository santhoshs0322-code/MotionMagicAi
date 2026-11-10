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

The current packs are 2,500, 5,000, 10,000 and 20,000 credits. **10,000 is Most Popular**.
The server enables packs only
after their matching flags are set. Create and activate each one-time product
in Play Console before enabling its flag.

| SKU | Grant | Suggested Indian Play price* | Flag |
|---|---:|---:|---|
| `motion_pack_2500` | 2,500 credits | ₹1,699 | `MOTION_AI_ENABLE_PACK_2500` |
| `motion_pack_5000` | 5,000 credits | ₹3,399 | `MOTION_AI_ENABLE_PACK_5000` |
| `motion_pack_10000` | 10,000 credits | ₹6,799 | `MOTION_AI_ENABLE_PACK_10000` |
| `motion_pack_20000` | 20,000 credits | Set in Play Console | `MOTION_AI_ENABLE_PACK_20000` |

*Suggested prices assume roughly ₹95/USD, a 15% Play fee and 18% tax included
in the customer price. Verify actual Play payouts and the current exchange rate
before launch. The old ₹249 price for 500 credits is too low for the new
25% margin target. The Android app intentionally shows the live Play purchase
sheet price rather than a hard-coded rupee amount.

For AWS, set `MOTION_AI_CREDIT_ONLY_MODE=true`,
`MOTION_AI_WELCOME_CREDITS=200`, and the verified per-model cost variables.
Credit-only mode enforces the 16,667/1,500 pricing settings in code, even if
older AWS environment entries still contain the previous markup.
The 500-credit products are retired from sale regardless of legacy top-up flags.
Deactivate `motion_credits_500_k7p4` in Play Console too. Existing credit balances,
purchase history, refunds and fully verified older payments remain supported.
Set `MOTION_AI_ENABLE_TOPUPS=false`. Set
`MOTION_AI_ENABLE_PACK_2500=true`, `MOTION_AI_ENABLE_PACK_5000=true`, and
`MOTION_AI_ENABLE_PACK_10000=true`, and `MOTION_AI_ENABLE_PACK_20000=true` only
after activating those products in Play Console. A six-scene 60-second story
using one 10-second Kling O3 Pro audio clip and five 10-second reference clips
costs about **3,110 customer credits** and **$9.80 in fal fees** at this rate
map. The example $10/day provider budget allows roughly one such story per day;
raise it deliberately as customer demand grows.

For the new one-time consumable product, use product ID `motion_pack_20000`,
name **20,000 AI Credits**, and description: “Add 20,000 AI credits to create images
and videos. One-time purchase. Purchased credits never expire.” Choose the price
and activate its purchase option in Play Console before enabling the server flag.
Migration `0023_credit_pack_20000` seeds the new product and disables the old ones;
Compose setup runs migration and rate/product synchronization automatically.
Rebuild Android to load the new SKU and move the Most Popular badge to 10,000.
Checkout selects an eligible standard buy option and passes its live offer token
to Play Billing. Rental/pre-order options are not used for permanent credits.
Provider integration reference: [Google Play one-time product purchase options](https://developer.android.com/google/play/billing/one-time-product-multi-purchase-options-offers).

Provider references: [Kling O3 Pro image to video](https://fal.ai/models/fal-ai/kling-video/o3/pro/image-to-video),
[Kling O3 Pro reference video](https://fal.ai/models/fal-ai/kling-video/o3/pro/video-to-video/reference).
