> Launch update: fal-only image/video generation; Nano Banana is excluded. Private financial reporting is available at `/owner/` after owner setup. See [Owner setup](backend-django/OWNER_SETUP.md) for permissions, billing reconciliation, cash records and Render environment values.

Before each Google Play upload, increase `versionCode` in `app/build.gradle.kts` by one: 11, then 12, then 13. The version code must be higher than the last uploaded bundle.

# MotionMagicAi

A native Kotlin / Jetpack Compose MotionMagic studio with a separate Django backend for accounts, server-priced generation jobs, transaction ledger and verified credit packs.

Local editing and native voice work without provider keys. **AI image/video generation is now connected through Django**, using Google plus fal.ai. Configure the server keys, costs, private storage, worker and wallet before generating. Fake keys never count as working credentials. See [provider setup and environment variables](backend-django/PROVIDERS.md).

## Open and build

Open this folder in Android Studio. Requirements: JDK 17+, Android SDK 36 and Build Tools 36.0.0. The project uses Gradle 8.13, Android Gradle Plugin 8.13.2, Kotlin 2.1.20 and Compose.

On this Windows workstation:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build-android.ps1 assembleDebug testDebugUnitTest
```

The script finds the installed JDK/SDK and keeps build caches and the debug signing key in the project. Its environment changes last only for the command. The first build needs internet access to download dependencies.

The APK is produced at `app/build/outputs/apk/debug/app-debug.apk`. It supports Android 8.0 / API 26 and newer. This is a debug-signed test APK, not a Play Store release.

The current integration build is [MotionMagic-AI-cloud-test.apk](artifacts/MotionMagic-AI-cloud-test.apk), configured for `https://motionmagicai.onrender.com`. Earlier APKs and editor screenshots remain in `artifacts/`.

To install on a connected test device:

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## What works

- A three-mode creation studio: Image to Video, Text to Video and Create Image, with a mode dropdown, expandable settings and a persistent Create action.
- Photo import through Android's photo picker, image validation, EXIF orientation handling, replace/remove controls and a restored selected photo.
- Model tiles, resolution segments, aspect-ratio tiles, video duration and generated sound. One job creates one output. Image creation keeps a separate prompt, model, quality and ratio draft.
- Model selection backed by live server availability, price confirmation, authenticated photo upload, credit reservation, job polling/recovery, bounded downloads and image/video result previews. All catalog models have adapters; unavailable or unpriced variants cannot run.
- Home creation cards, expandable local tools, searchable template rows, portrait template previews and a central creation button.
- Violet, pink, blue and teal creation cards with distinct icons, matching model tiles and bundled typography. Pricing compares free tools with AI credits and lists only enabled models with server prices. Subscriptions are not advertised as available.
- Bundled Plus Jakarta Sans in Regular, Medium, Semibold and Bold, applied to all Material typography roles without a runtime download. See [font provenance and license](docs/FONTS.md).
- Template previews apply mode-specific prompts and settings while preserving the user's uploaded photo. Sample artwork illustrates the look; it is never substituted for a required user photo.
- Separate video and audio editing workspaces: timelines, waveform preview, undo/redo, trim/split/append, speed, volume and background music. Video adds captions, color treatments and resolution selection; audio adds fades and AAC/M4A export. Unsupported advanced-AI and voice-cloning controls are not displayed.
- Large media documents use retained URI access rather than a full copy where supported. Thumbnail/waveform analysis has fixed budgets and runs away from the UI thread. See [editor performance and complexity](docs/EDITOR_PERFORMANCE.md).
- Device text-to-speech synthesis to WAV with five pitch/pace styles. An installed speech engine and English voice data are required. This is device synthesis, not the planned cloud voice model.
- Playback, gallery download on Android 10+, document export on older Android versions, audio document export, and Android sharing.
- Persistent projects, drafts and settings; project search, confirmation before deletion, missing-file recovery messages and cancellation cleanup.
- Optional local completion notifications, subject to Android notification permission.

Filters expose supported color treatments. Preview applies color/text, speed and background music; audio fades apply on export. Originals remain available. Editor sessions survive rotation but not process death.

For a walkthrough: **Sign in → choose a creation mode → add a photo or prompt → choose an enabled model/settings → Create → review the price → Confirm.** A funded server wallet is required. Check cloud generation recovers submitted requests after reconnecting. Create Image accepts an optional reference. Kling/PixVerse image-to-video follows the photo's shape; text-to-video uses the chosen ratio.

## Test mode and production work

Google login is implemented in `app/src/main/java/com/gamixtv/MotionMagicAI/auth/` and served by the separate [`backend-django`](backend-django/README.md) project. Deploy it on AWS and configure Google OAuth before using it; see [login setup](docs/LOGIN_SETUP.md). Local projects remain device-wide and do not sync with accounts.

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
