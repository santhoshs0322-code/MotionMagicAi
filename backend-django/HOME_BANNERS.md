# Home banner carousel

The five JPEGs supplied in `motionmagic_five_banners` are packaged under `template-assets/home_banners/` with a shared manifest. They appear when the owner feed publishes them, preserving all printed text without adding another heading over the artwork. Startup uses the last received owner catalogue; it no longer inserts bundled stock banners before the first feed arrives. No video players or generation calls are used.

Each banner initially stays for **four seconds**, and automatic rotation waits until the next image is ready. A pair of sentinel pages makes the wrap seamless without sweeping backwards through slides or allocating an unbounded pager. Each image covers the entire rounded 3:1 frame; there are no visible indicators, counters or neighboring slide previews. The old artwork and purple loading placeholder are removed. The currently loaded image stays visible while its replacement downloads. A first visit with no cached image waits to show the actual banner without blocking the rest of Home. Swiping and tapping still work; tap destinations match the image actually displayed. Automatic movement pauses while the banner is off-screen, held/swiped, a template preview is open, or the app is backgrounded. Android's accessibility timeout preference is respected; position remains available to screen readers.

Banner images use a dedicated 8 MiB decoded-image cache and a 32 MiB persistent cache, with at most two downloads and per-image deduplication. Cache keys include the API origin and immutable image path or bundled hash, excluding expiring signature parameters and text/timing revisions. Restarting the app or renewing a signed URL therefore reuses the image. The next banner is prefetched, decodes are bounded, corrupted cache files are replaced, and failed loads are throttled. These are public marketing assets; this cache never contains customer generation media.

The owner can edit banners at **https://api.motionmagicai.in/owner/showcase/banners/**. Upload replacement artwork, reorder positions, change the primary tap destination, set a 3–15 second display time, or unpublish. The title is an accessibility label; changing text printed in a JPEG requires replacement artwork. The template dashboard links to this page and its home mirror swaps the same published images on their configured schedule without a numbered line. Its CSS animation pauses on hover and shows the first image when reduced motion is requested; no browser JavaScript is required.

Uploads up to **5 MiB** remain supported. New uploads are validated and automatically converted to WebP under **300 KiB**, fitting within **1500 × 500 pixels** without enlargement or aspect distortion. Transparency is preserved, camera orientation is applied, and metadata is removed. Invalid, animated or excessive-pixel inputs are rejected before storage. This processing applies only to owner banners. Re-uploading always creates a new immutable image key.

For banners uploaded before this optimization, run once after deploying the backend:

```bash
docker compose -f compose.aws.yaml exec -T api python manage.py optimize_banner_images
```

The command skips bundled/already-optimized images, processes one bounded upload at a time, and preserves original S3 objects. It changes the database reference only if the owner has not uploaded a newer image concurrently, removes unused derivative objects, and invalidates the feed cache. It is safe to repeat. A 2.5 MiB upload is valid; no manual compression is required after this update. The very first uncached image still needs a network download. Any old bundled banner still explicitly published in the owner console remains part of the rotation until unpublished.

`sync_provider_rates` already calls `sync_showcase_templates`, which now also creates the five banner rows once. Imports preserve every subsequent owner change. The public `/api/v1/templates` response includes a `banners` array alongside the existing templates, with the same 30-second refresh behavior. Authoritative empty banner lists are persisted so unpublishing all five does not restore defaults on restart. Older app caches are migrated from their templates-only array format. The published banners are public marketing artwork; customer media stays in the existing account-specific storage.

The original banner feature introduced migration `0019_showcasebanner`; the loading and compression fix needs no additional migration. Deploy the backend and rebuild Android once:

```bash
cd ~/MotionMagicAi
git pull origin dev
cd backend-django
docker compose -f compose.aws.yaml up -d --build
```

The existing setup container applies migrations and imports banners automatically. No new environment variables or API keys are required. Subsequent owner edits need no new APK.
