# Custom owner credit grants

Open **https://api.motionmagicai.in/owner/** with the configured owner login. Go to **Users**, find the user by email, and select **Add credits** on that user's row. Alternatively open **Grant credits** and enter the app user ID. The form never loads the entire account list.

Enter 1–1,000,000 whole credits and a reason of 5–500 characters. **Review credit grant** displays the user's name, email, ID, amount and reason without changing the wallet. Confirm the signed review within ten minutes to apply it. The receipt, **Credit grants** history, **Wallet ledger**, and **Audit trail** show the record. History supports date and user filters.

Grants are non-expiring bonus/free credits. Existing balances and job holds are preserved. Any active monthly credits are consumed first, then free/bonus credits, then purchased top-ups. Grants survive monthly expiry and do not become Google Play purchases or cash revenue. The app receives the new amount on its next wallet refresh; no Android change is required.

Only the configured active Django superuser can grant credits. App JWTs, staff users and other superusers cannot use this feature. CSRF protection and a signed, owner-bound review protect confirmation. A unique grant ID, transactional wallet updates and a unique ledger reference make double-clicks/replays idempotent. The amount, reason, owner, timestamp and ledger entry are stored together; audit failure rolls back the wallet change.

Deploy the backend with the new `0021_owner_credit_grants` migration:

```bash
cd ~/MotionMagicAi
git pull origin dev
cd backend-django
docker compose -f compose.aws.yaml up -d --build
```

The setup container runs the migration automatically. No new environment variables or Android rebuild are required. This feature grants promotional credits; it does not record a payment received outside Google Play.
