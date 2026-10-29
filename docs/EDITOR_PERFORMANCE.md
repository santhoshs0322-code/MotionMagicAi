# Video and audio editors — 29 October 2026

The native editors follow the supplied Video and Audio Editors reference: dark panels, gradient export controls, a preview/waveform, layered timeline rows, a playhead, tool cards, filter thumbnails, voice cards and background-music shelves. Small phone screens scroll horizontally through tool rows and vertically through the workspace; the mockup's phone frame is not drawn inside the app.

## Implemented editing

Video: trim with precise numeric times, timestamp-only split, append/remove clips, undo/redo, speed, original/music volume, captions, eight color treatments, resolution selection and H.264/AAC MP4 export. Audio: import, trim/split/append, speed, volume, fades, background music and AAC/M4A export. There is one background music track, plus the original clip sequence. Fades apply to each original clip. Playback speed affects the complete mix.

The waveform is a sampled preview of the selected clip, not a sample-accurate waveform. Numeric trim controls provide precise timestamps independently of that visualization. Voice catalog previews use Android speech with local pitch/rate presets, not the identities depicted in the reference artwork. The included music loops are original synthesized 30-second demos; users can import their own recordings/music.

Advanced AI enhancement, background removal, stabilization, automatic subtitles, voice cloning, denoising and equalizer controls are not shown in the simple editor. No paid AI requests are made. Preview includes video color/text, speed and background music. Audio fades are applied at export. Gains above 100% are applied at export; preview volume is capped at 100%.

## Large-file path

1. The document picker retains read permission and stores a content URI. Supported document providers need no complete import copy and there is no 200 MB application limit.
2. If the provider cannot retain permission, import streams to private storage using a 64 KiB buffer. Free-space checks run every approximately 4 MiB, and failures remove the unfinished internal copy. This fallback needs storage proportional to the source size. Cloud-backed documents may still be downloaded by their provider.
3. Metadata is read on an I/O dispatcher. Preview starts without waiting for waveform/thumbnail analysis.
4. At most eight 160×90 thumbnails are requested per clip preview. The shared thumbnail LRU budget is 6 MiB. Android 8.0 uses placeholders because scaled frame retrieval begins at API 27; it does not fall back to decoding an oversized full-resolution bitmap.
5. Audio analysis samples 64 positions, with bounded decoder dequeue attempts per position. At most one analysis job runs at a time. The cache holds at most 24 small waveform arrays. Leaving/changing the source cancels the job and releases the extractor/decoder.
6. Each preview player targets a 16 MiB compressed-data buffer and a six-second buffer window. Codec/GPU/native allocations are additional and depend on resolution, channels and the device; 16 MiB is not a limit on the app's total RAM.
7. Export streams through Media3/MediaCodec, checks estimated output space, reports progress and releases codecs/deletes unfinished output on cancellation. The deadline scales with output duration (20× duration plus two minutes, at least three minutes, at most 24 hours). Keep the app open during export; background/process-death recovery is not implemented.

There is a targeted two-pass fallback for **multiple video clips combined with a speed change**. Media3 1.6.1's composition timestamps did not produce the correct duration in the regression fixture when combined directly. This case first flattens the edited timeline, then changes speed once on that continuous file. Both passes stream data; the intermediate is deleted on completion/failure/cancellation. It takes more time and temporary disk space than the ordinary single-pass path. Other edits use one pass.

## Time and space complexity

Let `N` be compressed source bytes, `F` decoded video frames, `W×H` frame resolution, `A` decoded audio samples, `C` timeline clips, and `U` undo snapshots.

| Operation | Time | Additional working space |
|---|---|---|
| Link a document | Container metadata/index/provider access; no application-level full-file scan | Metadata only; no complete source copy |
| Copy fallback | O(N) | 64 KiB copy buffer; O(N) persistent disk |
| Thumbnail/waveform preview | Fixed sample counts × provider seek/decode cost | Bounded thumbnail/waveform caches plus decoder buffers |
| Split/trim/change/undo | O(C) metadata operations | O(U×C) references; C ≤ 32 and U ≤ 30 |
| Audio export | O(A), plus codec work | Streaming buffers and channel state; no whole-track PCM array |
| Video export | Approximately O(F×W×H + A), depending on codec/effects/hardware | O(W×H) frame surfaces plus bounded queues; no whole-video frame list |
| Saved export | O(output bytes) writes | O(output bytes) disk; the special two-pass case also needs an intermediate |

Codec seeking is not guaranteed constant time: container indexes, GOP length, remote providers and device codecs affect it. A huge compressed file does not imply huge Java heap usage, but high-resolution decoding still requires appropriately sized frame surfaces. This is not a promise that every file or phone is supported.

## Measured fixture

An API 36 emulator opened a **1,073,741,824-byte (1 GiB) PCM WAV** representing **12,173,943 ms** (about 3 h 23 min), through a content URI, and exported a two-second range near its end to a valid audio-only M4A. The fixture uses sparse silent PCM data, not a 1 GiB compressed video benchmark. The first measurement recorded 168 ms for metadata open and process PSS snapshots of 192,168 KiB before and 192,404 KiB after; these are snapshots, not peak-memory measurements. The output was 37,090 bytes. See `artifacts/large-media-measurement.txt` for the latest test run.

## Limits to retain when publishing

Edits and undo survive activity rotation in a ViewModel, but unsaved editor sessions are not recovered after process death. Original imported documents must remain available. Local projects are device-wide. Preview samples cannot prove precise audio alignment; verify exports. The timeline supports up to 32 source clips and one background track. More complex multitrack arrangements, professional denoising, cloud voice models and the advanced AI tools require further implementation.

Sources for platform behavior: [Android document access](https://developer.android.com/training/data-storage/shared/documents-files), [Media3 transformations](https://developer.android.com/media/media3/transformer/transformations), and [multi-asset editing](https://developer.android.com/media/media3/transformer/multi-asset). Implementation targets the locally installed Media3 1.6.1 APIs.
