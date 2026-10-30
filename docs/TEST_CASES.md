# MotionMagic AI acceptance cases

This checklist tracks behavior needed for the reference design. An item being listed does **not** mean it has passed. The unit tests below cover local input rules; Android device behavior and external services need separate verification.

## Local rules implemented

`CreationValidationTest` contains six tests covering:

- Blank and whitespace-only prompts are rejected.
- A 500-character prompt is accepted; 501 characters are rejected.
- Photo animation may omit a motion prompt while still checking length, duration, and aspect.
- Multiple invalid fields produce actionable messages together.
- The editor choices of 5/10/15 seconds and 9:16/16:9/1:1 validate.
- Voice scripts require text and respect the same length limit, including non-English input.

These editor rules are supplemented by `CloudRateTest` and backend `studio.test_fal` for provider contracts, native-audio pricing and wallet/job recovery. Live providers still need account and paid-request verification; see the latest verification record.

## Cloud generation acceptance

- Sign in, choose an enabled service and confirm the actual server quote; insufficient credits must never start a provider request.
- Exercise text-to-image, reference-image editing, text-to-video and image-to-video across configured models. Check sound on/off, resolution, duration and source-shaped I2V output.
- Disconnect during submission and retry the saved quote/key; exactly one job and one settlement must exist. Close progress or kill/reopen the app, then use Check cloud generation.
- Interrupt a download or exhaust device storage; retain the server result and offer download recovery. Image results must show an image, not a video-player error.
- Verify provider rejection, moderation, invalid credentials, queue outage, and 24-hour unresolved-job expiry release customer holds. A known provider request must never be resubmitted on polling failure.
- Confirm the live price table contains only configured variants. Changing keys/costs requires rate sync. Native voice and simple editing remain free.

## Data persistence implemented; device verification required

`MotionStore` stores project metadata, settings, separate named creation drafts, and test-mode selection in private Android SharedPreferences. It does not store API keys, balances, or paid entitlements. Media files are managed by the media layer.

| Case | Expected outcome |
|---|---|
| New installation | Empty library; test mode enabled; supported default settings. |
| Save project and restart | Project metadata survives, newest first. |
| Save an existing project ID again | One updated project, without duplicate library cards. |
| Media file disappears | Metadata remains so the UI can explain that the file is unavailable. |
| Delete library entry | Metadata is removed; deletion of app-owned media is a separate operation. Gallery copies are not silently erased. |
| Save draft and leave screen | Prompt, style, duration, and aspect survive navigation/restart. Different draft keys stay separate. |
| Change default aspect | New drafts use the setting; previously saved drafts keep their own aspect. |
| Invalid persisted option | Unsupported aspect/duration falls back to a supported default. User-entered text is retained for correction. |
| Invalid metadata JSON | Read valid backup when possible; expose a warning; do not erase media files. Preserve damaged primary metadata before a subsequent write. |
| One malformed record among valid records | Load valid records and expose a warning. |
| Storage commit fails | Throw an actionable save error; UI must not report success. |
| Toggle test mode and restart | Preference survives; this preference never unlocks paid features or proves backend availability. |

## Screens and navigation — manual acceptance

| Screen / flow | Cases to verify |
|---|---|
| Launch / splash | First launch, recreation, background/resume, system back; splash never traps the user. |
| Home | Six feature tiles and all navigation tabs lead to their intended destination; hero and template artwork load or show a usable fallback. |
| Image to Video | Choose/cancel/change photo; inaccessible or malformed URI; unsupported media; large image; selected model/style/duration/aspect/resolution/audio; plan/provider locks; review-popup changes; duplicate confirmation; draft recovery. |
| Text to Video | Empty/whitespace/maximum-length prompt; keyboard and counter; selected style; invalid restored options; retry after correcting input. |
| AI Reels Maker | Template selection; missing source; supported portrait export; generation status; failed or cancelled job; preserve draft. |
| Templates | All/Reels/Love/Travel/Birthday filters; no matching cards; select a template then return; image loading failure; clear selected state. |
| AI Voice | Blank/long text; select each voice; speech engine unavailable; language pack missing; cancelled generation; interrupted playback; valid saved audio; disclose synthetic voice. |
| Video result | Play/pause/seek/end/replay; missing/unreadable file; share target unavailable; export failure; delete confirmation/cancel; editor receives the correct project. |
| Editor | Import/cancel; play/seek; trim boundaries; missing music; text overflow; effect/filter selection; no changes; unsaved changes; export cancellation/failure; inspect exported file for applied changes. |
| Projects | Empty library; multiple kinds; newest ordering; missing file; deletion and cancelled deletion; process restart; media created while the screen is inactive. |
| Settings | Values survive restart; notification permission allowed/denied; notification toggle reflects actual permission; aspect applies to new drafts; quality matches encoder support; auto-save handles errors. |
| Account | Accurate local/signed-in status; no fabricated identity or subscription; projects route; only verified one-time credit history when server billing is enabled; logout confirmation. |
| Plans | Displayed price versus actual billing product; unavailable products; no network; purchase pending/cancelled/declined; restored entitlement; already-owned item; renewal/refund/expiry. |
| General | Small screen, large font, TalkBack labels, portrait/landscape recreation, system back, keyboard overlap, long text, repeated taps, rapid navigation, offline state, low storage. |

## Media and job failure cases — manual acceptance

- Import uses Android's picker and handles denied, revoked, or expired URI access. Cancel keeps the prior selection.
- Invalid bytes, zero-byte files, oversized files, missing codecs, and unsupported MIME types produce a recoverable error.
- Work leaving the foreground does not accidentally submit a second paid request. Process recreation has a defined recovery state.
- Generation shows progress/status and prevents accidental duplicate submission. Cancellation stops local work; any provider cancellation/billing limitation is stated accurately.
- Saving/exporting only reports success after a usable file exists. Failed partial exports are cleaned up without deleting other projects.
- Sharing grants the receiving app temporary read permission through a content URI, never a private filesystem path.
- Export to gallery respects scoped storage and reports permission, full-storage, and MediaStore failures. A library project and an exported gallery copy are separate assets.
- Video dimensions/aspect/duration and audio presence are checked in the exported file, not inferred from a completed progress indicator.
- Playback and voice resources are released when leaving their screen. Incoming calls, audio focus loss, and Bluetooth changes do not leave stuck playback.

## Live-service cases required before production

| Integration | Required evidence |
|---|---|
| AI credentials | Server-only secret storage and authenticated client requests; no provider secret embedded in APK, logs, crash reports, or SharedPreferences. |
| Provider capabilities | Validate model access, input formats, duration, resolution, aspect, and supported voices. Explain any crop/upscale/extension needed by the editor options. |
| AI jobs | Handle queued/running/completed/failed/cancelled states; provider timeout, disconnect, malformed response, safety refusal, expired output, and unavailable model. |
| Paid usage | Server-authoritative credit reservation and reconciliation with idempotent requests; retry cannot double-charge; failure never claims a refund unless reconciled. |
| Rate limits | Handle rate limiting and exhausted budget with an actionable message; no unlimited automatic paid retries. |
| Billing | Google Play product configuration, signed purchase verification, acknowledgement, pending purchases, restore, refund/revocation, and renewal lifecycle. A local toggle never grants Pro. |
| Rewarded ads | Consent and SDK configuration; no-fill/load failure; skip versus completed reward; deduplicated server verification before awarding spendable credits. |
| Accounts | Authentication, expired sessions, device change, sign-out cleanup, ownership checks, account deletion, and cross-user isolation. |
| Privacy / support | Real support destination, accurate privacy/terms text, uploaded-media retention and deletion, and notification controls. |

## Test-mode limitations

Test mode is for evaluating the interface and supported local media operations. Any local photo motion, sample output, or device speech must be labeled accurately. A progress animation is not evidence of a cloud AI request.

Purchases, subscriptions, advertising rewards, credits, cloud accounts, and model-generated media must not be shown as successful without their real integrations. Keep unavailable actions explicit and recoverable. Paid-provider integration and Play Console setup require their own configured services and end-to-end tests.

## Editor acceptance checks

- Import local and cloud-provider audio/video; revoke source access and reopen; check the copy fallback, cancellation and insufficient storage.
- Open long recordings and large compressed videos on physical low-memory devices. Check seek latency, memory peaks, thermal throttling and codec errors separately from the sparse 1 GiB WAV fixture.
- Split near clip boundaries, append up to the 32-clip limit, remove clips, undo/redo, and rotate with edits in progress.
- Export both single clips and mixed timelines with speed changes; verify actual duration, audio synchronization, filter, caption, fades and channel behavior in the saved file.
- Exercise background music looping, import replacement, volume/mute, unsupported speech engines and missing voice data.
- Cancel during both passes of a multi-clip speed export, fill storage during export, background the app and terminate the process. Unsaved sessions and background export recovery are not supported.
- Test narrow screens, large fonts, TalkBack labels, landscape, older supported Android versions and physical GPU/codec combinations. The reference is adapted to scrolling phone layouts.

## Verification record

Actual results are recorded in [VERIFICATION.md](VERIFICATION.md). Local validation, native media creation/editing, selected UI flows, persistence and device speech have passed the specified tests. Items outside that record, including live AI, billing and ads, remain unverified or unavailable.
