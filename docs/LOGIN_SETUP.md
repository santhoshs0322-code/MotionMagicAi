# MotionMagic login and API

Authentication now belongs to the separate Django project at [`backend-django`](../backend-django/README.md). The Android adapter is `app/src/main/java/com/gamixtv/MotionMagicAI/auth/MotionMagicApi.kt`. The old CandyCraze/Node service is not the production MotionMagic backend.

Deploy on Render first using [the Render setup guide](../backend-django/RENDER_SETUP.md); the same Django service can move to AWS later. Build Android with the active Render or AWS HTTPS URL:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build-android.ps1 -PmotionAiApiBaseUrl=https://api.example.com assembleRelease
```

Without that property, the API base URL is empty and Google sign-in reports that the server is not configured; all local tools remain usable. No Django secret, JWT secret, Google service credential, Play credential, AWS credential or provider key belongs in the APK.

The backend returns its public Google Web OAuth client ID from `/auth/motion-ai/config`. Register the Android package and Play App Signing certificate in the same Google Cloud project. Set `MOTION_AI_GOOGLE_CLIENT_ID` to that project's Web client ID and enable `MOTION_AI_AUTH_ENABLED` only after deployment and a real sign-in test.

The app encrypts the persisted JWT with Android Keystore, validates issuer/audience/app/expiry locally for UX, and verifies it through `/me`. Server verification is authoritative. Sessions from the old Node backend are deliberately not accepted, so users sign in again after migration.

Use `backend-django/manage.py import_motion_accounts` for a MotionMagic-only Mongo export. Run a dry run first, pause old writes during final cutover, and preserve IDs. Do not import CandyCraze accounts, game saves, JWTs or revoked-session records.
