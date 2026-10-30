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

## Creation studio redesign — 29 October 2026, version 0.5.0

The creation screen now separates Image to Video, Text to Video and Create Image. Image to Video starts with an empty upload area, and Android's photo picker supplies the photo; bundled template artwork is never used as the user's required input. Video models and image models have separate chooser sheets. Compact settings expose ratio, quality, video duration and sound. Image generation keeps a separate prompt and quality draft. Templates preview their artwork and mode-specific prompt before applying settings, and retain an already-uploaded photo.

The final debug APK and Android test APK built successfully. All 17 JVM tests passed, and complete `lintDebug` passed with 0 errors and 30 advisory warnings. Both updated `AppFlowTest` cases passed together on the API 36 emulator in 27.674 seconds (`artifacts/creator-device-tests.txt`). They verify selectable Premium models, upgrade at generation, independent image/video prompts, ratio/duration/quality retention, native photo-picker open/cancel, photo import, template preview/application and photo preservation. Screenshot review also caught a real autosave issue: coroutine cancellation during rapid draft changes could be displayed as an error. Both video and image draft saves now propagate cancellation normally; the final device run verifies that prompt edits do not show that popup. Early device attempts exposed ambiguous text selectors, scrolling and cold-boot System UI timing; those attempts are not reported as passes. The final run was on the emulator, not the attached physical phone.

Screenshots: `artifacts/creator-upload.png`, `creator-text-page.png`, `creator-models.png`, `creator-settings.png`, `creator-image-generation.png` and `creator-template-preview.png`. Packaged APK: `artifacts/MotionMagic-AI-creator-test.apk`, with an adjacent SHA-256 file.

This is a UI/workflow implementation. Model adapters, live image/video generation, subscription entitlements and paid quotes are not connected to these new screens. A Free user can configure a Premium model but cannot generate it; no paid request or purchase was made. PixVerse's public creation documentation informed the input-first interaction order; MotionMagic keeps its own styling, text and supplied artwork.

## Phone screenshot reference update — 29 October 2026, version 0.6.0

The twelve screenshots in the user's specified phone folder (`Pictures/all`) informed the home/template catalog, mode dropdown, photo-upload area, large prompt field, collapsed settings pills, expanded model/resolution/ratio controls, output count and gradient Create action. MotionMagic retains its branding and supplied sample artwork. The implementation covers Create Image, Image to Video and Text to Video; the other reference tools are outside the supported scope. Model names are UI catalog previews, not validated production provider integrations.

`assembleDebug`, `assembleDebugAndroidTest`, `testDebugUnitTest` and `lintDebug` passed. All **17 JVM tests** passed; lint reported **0 errors and 30 advisory warnings**. Both updated `AppFlowTest` cases passed together on the API 36 emulator in **34.715 seconds**, recorded in `artifacts/reference-device-tests.txt`. They cover model selection without an immediate paywall, image resolution/ratio/count, video duration/ratio, separate drafts across mode switches and activity recreation, upgrade navigation, native picker open/cancel, photo import, template preview/application and photo preservation. This is activity recreation coverage, not a process-death recovery test. Existing media/editor suites were not rerun for this UI update.

Device tests exposed a real interaction issue: the photo-import snackbar covered the Create button. Creation-screen notifications now sit above the settings/Create controls, and the final import/template/create test passes without waiting for the notification to expire. An earlier test also incorrectly expected activity recreation to reset the retained screen; that expectation was corrected. Earlier failing logs do not count as passes.

Packaged debug APK: `artifacts/MotionMagic-AI-reference-test.apk` (0.6.0), with an adjacent SHA-256 file. Captures include `reference-home.png`, `reference-image-to-video.png`, `reference-text.png`, `reference-modes.png`, `reference-video-settings.png`, `reference-image-settings.png`, `reference-create-image.png` and `reference-template-preview.png`. Purple photo artwork in test captures is a generated import fixture. Physical-phone playback, small screens, large fonts and production providers remain unverified. No backend changes, new environment variables, purchases or live AI calls were needed for this update.

## Color and typography update — 29 October 2026, version 0.7.0

Home and compact template creation cards now use distinct violet, pink, blue and teal gradients, white icons and short supporting text. The mode chooser, model tiles and local-tool icons use the same palette; selected modes and models have a clear outline. A shared typography hierarchy adds stronger titles, tighter heading spacing and readable body line heights using Android's native sans-serif font. The brand reads MotionMagic AI.

The plan comparison now fits Free, Pro and Premium columns without horizontal scrolling. Rounded purple panels, tinted column backgrounds, alternating rows, mint Free checks, gold Pro stars and lavender Premium stars match the studio. Paid plans are still labeled coming soon, and the page explains that AI access/quality entries preview future availability. No prices, entitlements, API calls or backend configuration were enabled by this visual update.

`assembleDebug`, `assembleDebugAndroidTest` and `lintDebug` passed; lint reported **0 errors and 30 advisory warnings**. Both existing app-flow device tests passed on the API 36 emulator in **72.661 seconds** (`artifacts/premium-device-tests.txt`), including additional visibility checks for all three plan headers. Home, mode chooser and plan-table captures were visually reviewed. Unit and media suites were not rerun for this visual-only change. Large-font, narrow-screen and physical-device coverage remain outstanding.

APK: `artifacts/MotionMagic-AI-premium-ui-test.apk` with adjacent SHA-256. Screenshots: `premium-home.png`, `premium-modes.png`, `premium-video-settings.png`, `premium-image-settings.png` and `premium-plans.png` in `artifacts/`.

## Bundled font update — 29 October 2026, version 0.8.0

The app now bundles Plus Jakarta Sans in four static weights (400/500/600/700), replacing the system sans-serif family across all fifteen Material typography roles. Headings, buttons, labels and body text inherit the same family, with adjusted line heights. The original font license is included in APK assets. Source provenance and generation details are recorded in [FONTS.md](FONTS.md). Font metadata inspection confirmed the four weights and rupee glyph; APK inspection confirmed all four resources and the license are packaged. Font resources total 386,236 uncompressed bytes and require no runtime download.

`assembleDebug`, `assembleDebugAndroidTest` and `lintDebug` passed. Lint reported **0 errors and 30 advisory warnings**. Both existing app-flow tests passed on the API 36 emulator in **29.623 seconds** (`artifacts/font-device-tests.txt`), including plan-header visibility and creation settings/template navigation. Home and plan screenshots were reviewed for text wrapping and clipping at the emulator's default scale. Unit/media suites, older devices and large-font accessibility were not rerun for this typography-only update.

APK: `artifacts/MotionMagic-AI-font-test.apk` (0.8.0), with adjacent SHA-256. Screenshots: `font-home.png`, `font-plans.png`, `font-modes.png`. Live providers, pricing and billing behavior are unchanged.

## Google + fal integration — 30 October 2026

The current build connects the Android studio to Django for capability lookup, owned photo uploads, quoted credit confirmation, idempotent submission, saved-job recovery and bounded image/video downloads. All displayed image/video model families have Google or fal adapters. fal supports text and image input, provider-specific sizes, separate native-audio video rates, persistent queue IDs and private S3 result storage. Pricing shows configured server variants; it no longer claims unimplemented subscription entitlements. Existing studio UI and bundled-font work are preserved.

Verified: **32 Django tests** and **19 Android JVM tests** passed. `makemigrations --check --dry-run` found no missing migration. `assembleDebug` and `assembleDebugAndroidTest` passed with `motionAiApiBaseUrl=https://motionmagicai.onrender.com`. New coverage checks every fal request family and settings, frozen prices/config, cross-account media rejection, duplicate prevention, restart recovery, transport failure/refunds, native-audio rate matching and trusted bounded media transfers. All provider/S3 calls in backend tests are mocked; SQLite and local-memory cache are used.

`lintDebug` also passed. APK: `artifacts/MotionMagic-AI-cloud-test.apk`, with adjacent SHA-256. The app-flow instrumentation assertions were updated for one output and the live-credit pricing flow, but **device tests were not executed for this integration**. Real paid generations, provider permissions, Render worker execution, PostgreSQL contention, Play checkout and million-user throughput are not established by these tests. Android Play checkout and subscription lifecycle remain separate work. Read [provider setup](../backend-django/PROVIDERS.md) before enabling nonzero rates.
