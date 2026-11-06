# Image templates and the owner dashboard

The 30 supplied portraits and prompts live in `template-assets/image_templates/`: the original ten templates plus the 20-image mixed recreation pack. Android packages this same directory for immediate first-display previews; Django serves its reviewed JPEGs publicly with cache headers and an ETag. The new artwork is copied unchanged, adding about 736 KiB. These are catalogue examples. The app never substitutes their faces for a customer's uploaded photo.

`sync_provider_rates` automatically runs `sync_showcase_templates` after creating model rates. The seed command creates each template once using its stable UUID, initially selecting Qwen Image 720p and “Requires reference”. The original ten retain 9:16; the mixed pack uses 3:4, as specified in its identity-reference instruction. Its titles, categories and scene prompts match the supplied artwork rather than the unrelated numbered prompt list. Every mixed-pack prompt begins with the user's complete identity-reference instruction, including age-appropriate styling for children. Re-running setup preserves owner edits to title, category, prompt, poster, model, ratio, order, featured state and publication. Templates with disabled model rates remain hidden from the public feed.

Open **https://api.motionmagicai.in/owner/showcase/** using the existing owner login. All 30 templates appear in Manage templates. Choose Edit to change a prompt or title, upload replacement artwork, choose a supported model/ratio, or reorder it. Published image templates need a poster; a sample video is optional. Publishing a video template still requires an MP4 example. Unpublish removes a template from the app. Owner access and CSRF protections remain in force.

The template form is ordered Category → Image and title → Prompt and model → Publishing. Choose a saved category from the dropdown (draft categories are included), or choose Create new and enter its name. A new name is stored with the valid template, so an invalid form does not leave an empty category behind. Whitespace is normalized and an existing name with different capitalization is reused. Editing preselects the current category. Model options show readable names, quality, video audio mode and disabled status. These controls need no browser JavaScript. Image/video prompts now allow 1,000 characters in the owner form, backend quotation and Android input, retaining the full 622–665-character mixed-pack prompts. Device voice scripts retain their separate 500-character limit.

The public feed is the app's source of truth. Home and Templates refresh on foreground entry and every 30 seconds while visible; the creation template browser refreshes while open too. Owner writes invalidate the shared feed cache immediately. A successful feed replaces the entire local catalogue, including an empty feed, so unpublished templates do not return as a fallback. The last successful public feed is saved per API origin for offline display. A disconnected device can only show its last received state until it reconnects.

Bundled artwork loads from the APK when its content hash matches the server. Replacement posters load from the owner's private S3 catalogue using signed URLs and a new media revision. Three concurrent poster loads, downsampled remote images and a bounded 16 MiB bitmap cache keep scrolling responsive. Image previews do not create video players. The owner's mirror permits only same-origin catalogue images and existing S3 media through its content policy.

Applying one of these templates fills Create Image's prompt, model and ratio and requires the user's own photo. Copy prompt copies the exact displayed text. A selected template's photo requirement is saved with that account's draft. Clearing the template removes this requirement. Users can customize prompts and choose another available image model. The normal local credit estimate and authoritative Create quote are used; no extra template fee is added. The server also validates the published template and required source photo before reserving credits. Owner edits refresh catalogue previews; they do not overwrite an individual's edited creation draft.

Deploy the backend first; this update adds migration `0020_expand_template_prompts` to expand the template prompt column:

```bash
cd ~/MotionMagicAi
git pull origin dev
cd backend-django
docker compose -f compose.aws.yaml up -d --build
docker compose -f compose.aws.yaml exec -T api python manage.py sync_provider_rates
```

The existing setup container runs migrations and imports the new templates automatically. The explicit rate sync above is safe to repeat. Check `/api/v1/templates`, then `/owner/showcase/`. Rebuild and distribute Android for the new bundled images and longer prompt support; older APKs still enforce their previous 500-character limit. Later owner edits within the supported limit require no new APK. No new environment variables or generation-provider key are required, and the import performs no paid generations.
