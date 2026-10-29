# MotionMagicAi

A native Kotlin / Jetpack Compose MotionMagic studio with a separate Django backend for accounts, server-priced generation jobs, transaction ledger and verified credit packs.

**This build runs without an API key.** It makes real local media files, but does not call a generative AI service. A fake key is never treated as a working credential.

## Open and build

Open this folder in Android Studio. Requirements: JDK 17+, Android SDK 36 and Build Tools 36.0.0. The project uses Gradle 8.13, Android Gradle Plugin 8.13.2, Kotlin 2.1.20 and Compose.

On this Windows workstation:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build-android.ps1 assembleDebug testDebugUnitTest
```

The script finds the installed JDK/SDK and keeps build caches and the debug signing key in the project. Its environment changes last only for the command. The first build needs internet access to download dependencies.

The APK is produced at `app/build/outputs/apk/debug/app-debug.apk`. It supports Android 8.0 / API 26 and newer. This is a debug-signed test APK, not a Play Store release.

The packaged Django-ready build is [MotionMagic-AI-django-test.apk](artifacts/MotionMagic-AI-django-test.apk) (0.4.0), with an adjacent SHA-256 file. Earlier editor screenshots remain in `artifacts/`; the current editor intentionally shows fewer controls.

To install on a connected test device:

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## What works

- Splash, home, image-to-video, text-to-video, reels, voice, templates, projects, result, editor, account, settings and plan screens.
- Photo import through Android's file picker, image validation, EXIF orientation handling and a restored selected photo.
- Local photo animations exported as playable H.264 MP4 files: 5, 10 or 15 seconds; 9:16, 16:9 or 1:1; 540-pixel short edge.
- Text/reel prompts are retained as project titles. In test mode, the visuals animate sample artwork; prompts do not generate new scenes.
- Separate video and audio editing workspaces: timelines, waveform preview, undo/redo, trim/split/append, speed, volume and background music. Video adds captions, color treatments and resolution selection; audio adds fades and AAC/M4A export. Unsupported advanced-AI and voice-cloning controls are not displayed.
- Large media documents use retained URI access rather than a full copy where supported. Thumbnail/waveform analysis has fixed budgets and runs away from the UI thread. See [editor performance and complexity](docs/EDITOR_PERFORMANCE.md).
- Device text-to-speech synthesis to WAV with five pitch/pace styles. An installed speech engine and English voice data are required. This is device synthesis, not the planned cloud voice model.
- Playback, gallery download on Android 10+, document export on older Android versions, audio document export, and Android sharing.
- Persistent projects, drafts and settings; project search, confirmation before deletion, missing-file recovery messages and cancellation cleanup.
- Optional local completion notifications, subject to Android notification permission.

Filters expose supported color treatments. Preview applies color/text, speed and background music; audio fades apply on export. Originals remain available. Editor sessions survive rotation but not process death.

For a quick walkthrough: **Image to Video → 5s → Generate Test Video → Edit Further → Text → Apply → Export → Download.** Open **Audio Editor** from Home to import an audio file, or from the video editor to edit/extract its sound.

## Test mode and production work

Google login is implemented in `app/src/main/java/com/motionmagic/ai/auth/` and served by the separate [`backend-django`](backend-django/README.md) project. Deploy it on AWS and configure Google OAuth before using it; see [login setup](docs/LOGIN_SETUP.md). Local projects remain device-wide and do not sync with accounts.

There is no configured live generation provider or Play product in this test build. The app lists its working local services. The backend hides cloud services and products until their gateway, rate, budget, storage and purchase verification are enabled. See [API costs and margin planning](docs/API_COSTS_AND_MARGIN.md).

The Django backend supplies authenticated quotes, idempotent wallet reservations, a durable PostgreSQL job outbox, provider reconciliation, S3 ownership, purchase history and refund-aware ledger entries. A private provider gateway still needs to be selected and validated before enabling a rate. No provider name or price is treated as production-ready in source.

One-time Google Play credit verification and reconciliation are implemented server-side but remain disabled until credentials and real products are configured and tested. Subscriptions, ads and referral rewards are absent. Release signing, licensed production artwork, a support destination, privacy/terms documents and device coverage are still required before publishing.

## Verification

Pure Kotlin tests cover input validation. Android instrumentation tests exercise actual codecs and storage:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build-android.ps1 connectedDebugAndroidTest
```

`MediaSmokeTest` checks all three output aspect ratios, playable frames and duration, cancellation cleanup, trim/caption/filter/music output with an audio track, and persistent metadata. See [the acceptance checklist](docs/TEST_CASES.md) and [the verification record](docs/VERIFICATION.md) for actual results and remaining limits.

Reference artwork is included in `app/src/main/assets/showcase.png` and cropped at display time. It is sample artwork supplied for this task, not a claim of production redistribution rights.
