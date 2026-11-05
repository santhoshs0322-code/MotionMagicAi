> Launch update: fal-only image/video generation; Nano Banana is excluded. Private financial reporting is available at `/owner/` after owner setup. See [Owner setup](backend-django/OWNER_SETUP.md) for permissions, billing reconciliation, cash records and Render environment values.

Play Console requires every new bundle to have a higher version code. The next code is read from `version.properties` and advances after a successful release bundle. New builds call `https://api.motionmagicai.in` by default; an explicit `-PmotionAiApiBaseUrl=https://api.example.com` can select another HTTPS test deployment. Configure the domain's DNS, Caddy HTTPS and backend environment before publishing the domain build. Release builds use R8 code shrinking and obfuscation.

# MotionMagicAi

A native Kotlin / Jetpack Compose MotionMagic studio with a separate Django backend for accounts, server-priced generation jobs, transaction ledger and verified credit packs.

For the small-launch AWS deployment and GoDaddy domain setup, follow [the EC2 cutover guide](backend-django/AWS_FREE_TIER_SETUP.md). It preserves the current PostgreSQL database and Valkey service. Associate an Elastic IP with EC2 and point `api.motionmagicai.in` at it; the main and `www` domains temporarily redirect to GamixTV. The owner panel is `https://api.motionmagicai.in/owner/`.

Device Voice works without a provider key. **AI image/video generation is connected through Django** using fal.ai. Configure the server key, costs, private storage, worker and wallet before generating. Fake keys never count as working credentials. See [provider setup and environment variables](backend-django/PROVIDERS.md).

## Open and build

Open this folder in Android Studio. Requirements: JDK 17+, Android SDK 36 and Build Tools 36.0.0. The project uses Gradle 8.13, Android Gradle Plugin 8.13.2, Kotlin 2.1.20 and Compose.

On this Windows workstation:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build-android.ps1 assembleDebug testDebugUnitTest
```

The script finds the installed JDK/SDK and keeps build caches and the debug signing key in the project. Its environment changes last only for the command. The first build needs internet access to download dependencies.

The APK is produced at `app/build/outputs/apk/debug/app-debug.apk`. It supports Android 8.0 / API 26 and newer. This is a debug-signed test APK, not a Play Store release.

The earlier [MotionMagic-AI-cloud-test.apk](artifacts/MotionMagic-AI-cloud-test.apk) was built for the former Render endpoint. Existing APKs keep their compiled address; rebuild and publish an updated bundle to use the new domain. Historical APKs and editor screenshots remain in `artifacts/`.

To install on a connected test device:

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## What works

- A three-mode creation studio: Image to Video, Text to Video and Create Image, with a mode dropdown, full-screen model settings, reference/prompt cards and a persistent Create action showing estimated credits.
- Light and dark appearances across the app, including sign-in, purchases and dialogs. Use the sun/moon button beside the Home credit badge or the appearance switch in Settings. The device remembers the last selection; older installations default to Dark.
- My Projects uses a mixed image/video grid with All, Images, Videos and Voice filters. Completed cloud tiles show small private previews of their generated outputs, cached by account; device copies replace their cloud tiles without duplication. Open a cloud tile to download, or use a device tile's options to continue a saved video or delete a local copy.
- Photo import through Android's photo picker, image validation, EXIF orientation handling, replace/remove controls and a restored selected photo.
- Model tiles, resolution segments, aspect-ratio tiles, video duration and generated sound. One job creates one output. Image creation keeps a separate prompt, model, quality and ratio draft.
- Model selection backed by live server availability, price confirmation, authenticated photo upload, credit reservation, job polling/recovery, bounded downloads and image/video result previews. All catalog models have adapters; unavailable or unpriced variants cannot run.
- Home creation cards, quick creation tools, searchable template rows, portrait template previews and a central creation button.
- Violet, pink, blue and teal creation cards with distinct icons, matching model tiles and bundled typography. Pricing compares free tools with AI credits and lists only enabled models with server prices. Subscriptions are not advertised as available.
- Bundled Plus Jakarta Sans in Regular, Medium, Semibold and Bold, applied to all Material typography roles without a runtime download. See [font provenance and license](docs/FONTS.md).
- Template previews apply mode-specific prompts and settings while preserving the user's uploaded photo. Sample artwork illustrates the look; it is never substituted for a required user photo.
- Device text-to-speech synthesis to WAV with five pitch/pace styles. An installed speech engine and English voice data are required. This is device synthesis, not the planned cloud voice model.
- Playback, gallery download on Android 10+, document export on older Android versions, audio document export, and Android sharing.
- Persistent projects, drafts and settings; project search, confirmation before deletion, missing-file recovery messages and cancellation cleanup.
- Optional local completion notifications, subject to Android notification permission.

For a walkthrough: **Sign in → choose a creation mode → add a photo or prompt → choose an enabled model/settings → Create → review the price → Confirm.** A funded server wallet is required. Submitted jobs appear in My Projects with a stage estimate; up to three can run at once. Refresh projects recovers submitted requests and reads saved database status; Download to device saves a completed result to the phone. My Projects loads 30 cloud creations per page with Load more, deduplicates downloaded copies, and retains loaded history during active-job updates. Search applies to loaded projects. Create Image accepts an optional reference. Kling/PixVerse image-to-video follows the photo's shape; text-to-video uses the chosen ratio.

**Story audio:** Kling O3 Story Reference preserves audio already present in the source video through fal's `keep_audio` parameter; it does not generate a soundtrack for silent input. The app checks for an audio track when selecting a reference, disables audio preservation if none exists, and explains the behavior before confirmation. Use Kling O3 image-to-video or text-to-video with Generate Audio for new sound.

## Test mode and production work

Google login is implemented in `app/src/main/java/com/gamixtv/MotionMagicAI/auth/` and served by the separate [`backend-django`](backend-django/README.md) project. Deploy it on AWS and configure Google OAuth before using it; see [login setup](docs/LOGIN_SETUP.md). Local media and drafts are scoped to the signed-in account on that device; cloud generation history belongs to the server account.

No provider key or paid product is embedded in the app. Server configuration decides which cloud variants are available. Rate defaults remain zero, and no live paid provider call is part of the automated tests. Use [PROVIDERS.md](backend-django/PROVIDERS.md) for current model contracts and pricing configuration.

The Django backend supplies authenticated quotes, idempotent wallet reservations, a durable PostgreSQL job queue, Google/fal adapters, S3 ownership, purchase history and refund-aware ledger entries. No private gateway is required for these models. Cost allowances must be configured and reviewed against provider invoices.

One-time Google Play credit verification and reconciliation are implemented server-side but remain disabled until credentials and real products are configured and tested. Subscriptions, ads and referral rewards are absent. Release signing, licensed production artwork, a support destination, privacy/terms documents and device coverage are still required before publishing.

## Verification

Pure Kotlin tests cover input validation. Android instrumentation tests exercise actual codecs and storage:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build-android.ps1 connectedDebugAndroidTest
```

`MediaSmokeTest` checks all three output aspect ratios, playable frames and duration, cancellation cleanup, trim/caption/filter/music output with an audio track, and persistent metadata. See [the acceptance checklist](docs/TEST_CASES.md) and [the verification record](docs/VERIFICATION.md) for actual results and remaining limits.

Reference artwork is included in `app/src/main/assets/showcase.png` and cropped at display time. It is sample artwork supplied for this task, not a claim of production redistribution rights.
