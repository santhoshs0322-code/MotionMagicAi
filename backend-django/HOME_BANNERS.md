# Home banner carousel

The five JPEGs supplied in `motionmagic_five_banners` are packaged under `template-assets/home_banners/` with a shared manifest. They appear in the supplied order, preserving all printed text without adding another heading over the artwork. Android loads the bundled images immediately; they share the existing bounded poster cache. No video players or generation calls are used.

Each banner initially stays for **four seconds**. The app slides forward through **1 → 2 → 3 → 4 → 5 → 1** continuously. A pair of sentinel pages makes the wrap seamless without sweeping backwards through slides or allocating an unbounded pager. Swiping in either direction and tapping the five indicators also work. Timing restarts after interaction. Automatic movement pauses while the banner is off-screen, the user holds/swipes it, a template preview is open, or the app is backgrounded. Android's accessibility timeout preference is respected.

The owner can edit banners at **https://api.motionmagicai.in/owner/showcase/banners/**. Upload replacement artwork, reorder positions, change the primary tap destination, set a 3–15 second display time, or unpublish. The title is an accessibility label; changing text printed in a JPEG requires replacement artwork. The template dashboard links to this page and its home mirror shows the same first published banner and sequence.

`sync_provider_rates` already calls `sync_showcase_templates`, which now also creates the five banner rows once. Imports preserve every subsequent owner change. The public `/api/v1/templates` response includes a `banners` array alongside the existing templates, with the same 30-second refresh behavior. Authoritative empty banner lists are persisted so unpublishing all five does not restore defaults on restart. Older app caches are migrated from their templates-only array format. The published banners are public marketing artwork; customer media stays in the existing account-specific storage.

This update adds migration `0019_showcasebanner`. Deploy the backend and rebuild Android once:

```bash
cd ~/MotionMagicAi
git pull origin dev
cd backend-django
docker compose -f compose.aws.yaml up -d --build
```

The existing setup container applies migrations and imports banners automatically. No new environment variables or API keys are required. Subsequent owner edits need no new APK.
