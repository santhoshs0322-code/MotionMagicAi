# Verification record

Verified on 28 October 2026 with the Android 36 x86_64 Google Play emulator (Pixel 6 profile), Java 21, Gradle 8.13 and Android Build Tools 36.0.0.

## Passed

| Check | Result |
|---|---|
| Debug APK build | Passed; installable on the emulator |
| `CreationValidationTest` | 6 tests passed |
| `MediaSmokeTest` | 4 instrumentation tests passed |
| `AppFlowTest` | 2 interaction tests passed |
| `DeviceVoiceTest` | 1 instrumentation test passed |
| Android `lintDebug` | Passed with no errors; advisory warnings remain |

Total: **13 automated tests passed** across local and device runs.

The media tests decoded actual video frames, checked all three aspect ratios and five-second duration, verified cancellation removes incomplete output, and verified a three-second edited MP4 containing both video and mixed audio. The edit included a caption and warm colour filter. The persistence test reopened metadata, drafts and settings and retained a missing-file project record.

The UI tests checked empty-prompt validation and unavailable purchase messaging, then created a five-second photo animation, edited it with a caption, downloaded it through MediaStore, cancelled project deletion and opened the project library. The first UI run hit a test timing issue while the download snackbar covered the next control; the test now waits for that message to disappear before proceeding, and the complete flow passed.

The voice test used the emulator's Google Android speech engine, generated a nonempty file and verified RIFF/WAVE headers. Other devices still need a working speech engine and voice data.

## Visual checks and artifacts

- `artifacts/home.png`: native app home screen captured from the emulator.
- `artifacts/projects.png`: persisted projects captured after generation and editing.
- `artifacts/result.png`: generated video result screen.
- `artifacts/sample-preview.mp4`: actual five-second edited preview exported by this app. This is a local photo animation, not a generative AI video.
- `artifacts/MotionMagic-AI-test.apk`: final debug-signed test build.
- `artifacts/ui-voice-tests.txt`: successful interaction and device voice test output.

## Limits

This verifies the implemented local test mode on one emulator. It does not establish that every possible device, codec, permission, storage condition or accessibility setting has been tested. Older supported Android versions and physical devices need further coverage.

Live AI, provider authentication, billing and advertisements are not implemented or certified by these tests. Remote login was added in the update below. See `TEST_CASES.md` for the broader production acceptance checklist.

## Login integration update — 28 October 2026

Server commit `658f3d9` was pushed to `auth_server/main`; no deployment was manually triggered. The backend runs a separate MotionMagic module with `MOTION_AI_` configuration. All **31 server tests** passed on Node 24.16.0: 9 MotionMagic HTTP/configuration tests and 22 existing payment/auth regression tests. MotionMagic tests inject Google verification and a storage adapter; they do not verify the production Google/MongoDB setup.

Android `assembleDebug`, `testDebugUnitTest`, `lintDebug` and `assembleDebugAndroidTest` passed. The **10 JVM tests** include 4 new authentication/configuration tests and 6 existing input validation tests. Lint reported 0 errors and 28 advisory warnings, mostly version updates and style suggestions. The **3 new emulator tests** passed on API 36: JWT app/issuer checks, encrypted session round-trip/deletion/server binding, and Profile sign-in navigation back to the studio. Existing media instrumentation was not repeated for this login change.

The new artifact is `artifacts/MotionMagic-AI-auth-test.apk`, with SHA-256 in its adjacent `.sha256` file. The earlier `MotionMagic-AI-test.apk` remains available as the original local-only build.

The provided HTTPS service answered `/health` and returned 401 from unauthenticated `/api/me`. Its health body identified Monster Merge, so the deployment guide explicitly requires checking the Render source repository/branch. Real Google sign-in, release signing and production database permissions remain unverified until deployment and OAuth setup. No production identity was created, no AI credentials were purchased, and no paid generation was made.

## Editor update — 29 October 2026

The 0.3.0 editor build adds separate native video and audio workspaces following the supplied reference, adapted to scrolling phone layouts. Artifacts are `MotionMagic-AI-editors-test.apk` and its adjacent SHA-256 file; screen captures are `studio-video.png`, `studio-audio.png` and `studio-audio-library.png` in `artifacts/`.

Final `assembleDebug`, `assembleDebugAndroidTest`, `testDebugUnitTest` and `lintDebug` passed. Lint reports 0 errors and 30 advisory warnings. Across this update, **23 distinct tests passed**: 14 JVM tests, 4 studio media tests, 2 studio UI tests, 2 existing app-flow tests and the existing local-render cancellation test. The final three regression tests passed together after the progress fix (`artifacts/editor-final-regression.txt`). The unchanged editor pipeline's six media/UI tests had passed earlier; they were not all repeated after the isolated generation-progress fix. Older failed/interrupted test logs are retained and are not passing results.

The four `StudioMediaTest` cases passed: timed multi-clip video with speed/filter/caption/music, a 1 GiB content-URI WAV with a near-end two-second export, audio speed/pitch/fades/tone processing with bounded waveform analysis, and PCM fade behavior/cancellation cleanup. The multi-clip speed regression required a streaming two-pass fallback to produce the expected 2.5-second duration. Both `StudioUiTest` cases passed, including real MP4 and M4A exports from the editor screens.

The sparse 1 GiB fixture's first metadata-open measurement was 168 ms; before/after PSS snapshots were 192,168/192,404 KiB. These are not peak-memory measurements or a compressed-video benchmark. See [EDITOR_PERFORMANCE.md](EDITOR_PERFORMANCE.md) for buffer/cache budgets, time/space complexity and limitations.

The first emulator graphics backend repeatedly crashed the Windows QEMU process on the audio screen. Audio UI verification succeeded with `-gpu swangle -feature -Vulkan`. The broader app-flow test also exposed a loading wait in the test and a worker-thread progress notification; the test now waits for enabled editor controls, and progress travels through a conflated channel to Main while EGL rendering stays on its worker thread.

Cloud AI tools, paid voice models, process-death draft recovery and background export recovery are not implemented. The test fixture does not prove support for every codec/provider/device or multi-gigabyte compressed video. No backend changes or new environment variables are required for these local editor features.

## Dedicated Django backend and simplified app — 29 October 2026

`backend-django/` is a separate MotionMagic service. Nine Django tests passed for the 150% markup formula, wallet holds and releases, idempotent jobs, actual-cost settlement, authentication/revocation, disabled-product visibility, unknown-field rejection, duplicate Play purchase protection and refund reversal. `manage.py check --deploy` passed with representative production settings, and migration drift check reported no changes. These tests use SQLite and mocked Play responses; PostgreSQL row-lock behavior, Redis, S3, Google identity, Play Developer API, the provider gateway and AWS capacity still require integration/load tests.

Android 0.4.0 builds without an API URL by default, so local services work while login clearly remains unconfigured. Supply `-PmotionAiApiBaseUrl=https://…` for the AWS deployment. The app now lists its completed local services instead of proposed subscriptions, prices and rewards. The simple editor exposes trim, split, speed, volume, text, filters, fades, music and export; unsupported advanced AI, voice-cloning, denoise and equalizer controls are absent.

`assembleDebug`, `assembleDebugAndroidTest`, `testDebugUnitTest` and `lintDebug` passed; lint has zero errors and advisory warnings only. The existing generate/edit/export/download device flow and guest authentication navigation passed. The corrected available-services interaction passed separately in `artifacts/django-services-ui-test.txt`.
