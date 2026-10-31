# fal.ai credit map

Reviewed on 31 October 2026. MotionMagic uses integer credits: **100 customer
credits have a US$1 face value**. The `*_COST_PAISE` environment names are
legacy names; their values below are **raw fal provider USD-cent allowances**,
not Indian paise. With `MOTION_AI_MARKUP_BPS=40000`, a customer is charged five
credits for every raw-cost credit, giving an **80% gross margin before Google
Play/payment fees, tax, S3 and support costs**.

| Service | fal cost basis | Raw allowance | Customer charge |
|---|---:|---:|---:|
| Qwen Image 720p | $0.02, one billed MP | 2 | 10 credits/image |
| Qwen Image 1080p | $0.02 x two billed MP | 4 | 20 credits/image |
| GPT Image 2.5 Flare/Sunburst 1K high | conservative generation/edit allowance | 7 | 35 credits/image |
| GPT Image 2.5 Flare/Sunburst 2K high | conservative generation/edit allowance | 25 | 125 credits/image |
| GPT Image 2 1K high | $0.211 canonical square maximum | 22 | 110 credits/image |
| GPT Image 2 2K high | conservative high-quality allowance | 42 | 210 credits/image |
| Seedream 5 Pro 1K | $0.0675 | 7 | 35 credits/image |
| Seedream 5 Pro 2K | $0.135 | 14 | 70 credits/image |
| Kling O3 Standard 720p, no audio | $0.084/second | 9/sec | 45 credits/sec |
| Kling O3 Standard 720p, audio | $0.112/second | 12/sec | 60 credits/sec |
| PixVerse V6 720p, no audio | $0.045/second | 5/sec | 25 credits/sec |
| PixVerse V6 720p, audio | $0.060/second | 6/sec | 30 credits/sec |
| PixVerse V6 1080p, no audio | $0.090/second | 9/sec | 45 credits/sec |
| PixVerse V6 1080p, audio | $0.115/second | 12/sec | 60 credits/sec |

Qwen bills images by rounding up to a megapixel. GPT Image prices vary by
prompt, output dimensions and reference image, so the configured values are
deliberately rounded upward. Do not turn on a zero-cost rate: zero deliberately
keeps it unavailable. Nano Banana remains excluded for this fal-only launch.

Seedream 5 Lite, Seedream 4.5 and Kling O3 Pro/1080p stay disabled until their
exact complete charge is confirmed in the fal account that will be billed. That
prevents selling an unpriced service at a false margin.

## Packs and launch guardrails

| Offering | Store price | Credit grant | Maximum fal allowance at this map |
|---|---:|---:|---:|
| Verified Google welcome | Free, once | 100 | $0.20 |
| Creator | INR 499 one-time pack | 600 | $1.20 |
| Legend | INR 999 one-time pack | 1,200 | $2.40 |

Creator and Legend are intentionally one-time credit packs, not recurring
subscriptions. Create the corresponding Play products as `motion_creator_credits`
and `motion_legend_credits`, then set `MOTION_AI_ENABLE_CREDIT_PACKS=true` only
after Play server verification is configured. The deployment synchronisation
enables those seeded products without shell access; until then no unsupported
checkout is offered.

For a safe first launch use `MOTION_AI_DAILY_PROVIDER_BUDGET_PAISE=1000` and
`MOTION_AI_BUDGET_SHARDS=1`: that limits raw fal spend to **$10/day**. A 64-shard
AWS deployment needs a substantially larger daily cap, because each shard must
be able to reserve the largest single generation.

Sources: [Qwen Image](https://fal.ai/models/fal-ai/qwen-image), [GPT Image
2.5](https://fal.ai/gpt-image-2.5), [GPT Image
2](https://fal.ai/models/openai/gpt-image-2), [Seedream 5
Pro](https://fal.ai/models/bytedance/seedream/v5/pro/text-to-image), [Kling
O3 Standard](https://fal.ai/models/fal-ai/kling-video/o3/standard/image-to-video)
and [PixVerse V6](https://fal.ai/models/fal-ai/pixverse/v6/text-to-video).
