# API cost, pricing and margin

MotionMagic does not hard-code provider prices or advertise unconfigured models. An administrator enables a `Rate` only after verifying account access, actual billable units, quality, latency, safety rules and failure/cancellation charges. `/api/v1/capabilities` exposes only enabled rates while the gateway, private S3 bucket and positive daily budget are configured.

All provider calls go through the dedicated Django worker and a private, authenticated provider gateway. Keys stay in server-side secret storage. Android receives a quote and job ID, never a provider key.

## 150% markup

The requested “150% margin” is implemented as a **150% markup**, because gross margin cannot exceed 100%:

`net receipts target = complete variable cost × 2.5`

`customer charge = ceil(net receipts target / (1 - configured store fee))`

At a zero store fee, ₹100 complete variable cost becomes ₹250 customer charge: ₹150 gross profit, 150% markup and 60% gross margin. If the configured store fee is 15%, the rounded charge becomes ₹295, leaving about ₹251 after the fee and about ₹151 before tax, refunds, support, AWS, foreign exchange and other costs.

The server stores integer paise and freezes the cost, markup and fee basis points in each five-minute quote. Complete variable cost must include provider input/output, expected retries/failures, gateway, storage/egress and per-job operational allowances. Taxes are handled separately according to the actual store settlement and jurisdiction.

## Transaction flow

1. The app requests a server quote. The server computes billable units from its own rate row.
2. The app submits the quote once with a stable idempotency key.
3. PostgreSQL locks the wallet, reserves the maximum charge, records an immutable ledger entry and commits a durable queued job in one transaction.
4. A separate worker submits by the MotionMagic job UUID. After an ambiguous timeout it reconciles by that UUID instead of blindly creating another paid request.
5. On success, the backend validates S3 output and provider-reported actual cost, charges the price derived from actual cost up to the quote maximum, and releases the remaining hold. Failure/cancellation releases the hold.
6. Daily provider-cost buckets and per-account active-job limits bound financial exposure before a request reaches a provider.

One-time credit packs remain hidden until Google Play server verification is configured. A unique purchase-token hash prevents replay; the encrypted token supports later consumption/refund reconciliation. Refunds create a reversing ledger entry and may leave debt when credits were already spent. Subscriptions, ads and referral credits are not implemented or displayed.

Final rates must be recalculated from real provider invoices and Google Play settlement reports. Do not populate production `Rate` or `Product` rows from mockups.
