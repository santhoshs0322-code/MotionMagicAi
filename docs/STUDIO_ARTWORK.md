Artwork generated with the built-in image-generation tool using the user's credit-page screenshot as the hero reference. Original generation outputs remain in the Codex image library. Runtime copies are bundled WebP files, with alpha preserved for the coins.

Assets:
- `app/src/main/assets/studio/credits_hero.webp` — 1200 × 800, approximately 114 KB.
- `app/src/main/assets/studio/credit_coins.webp` — 512 × 512, approximately 64 KB; shared neutral illustration tinted in Compose.
- `app/src/main/assets/studio/long_story_home.webp` — 1200 × 600, approximately 74 KB.

The app decodes artwork off the main thread, serializes decoding and shares an 8 MB bitmap cache. No image network request is required. Text, navigation, balance, purchase state and localized Play prices are native UI.

## creditHeroPrompt

Use case: ads-marketing. Create ONE polished raster hero background asset for MotionMagic AI Android credit-shop screen, using the attached screenshot as the layout and artwork reference, NOT an entire UI screenshot. Landscape 3:2 canvas. Deep near-black navy #03031b starry magical background. Keep the LEFT 42 percent mostly empty dark navy because native UI will overlay headline. On the RIGHT a beautiful premium 3D stylized young adult woman with long dark flowing hair, wearing purple luminous over-ear headphones with a white M monogram, looking upward in wonder, purple-blue jacket, torso visible. Exactly like the visual language of reference: brilliant violet and magenta neon ribbon spiraling around her waist, glowing light streaks; floating tilted square thumbnail frames around her showing fantasy castle, cute orange kitten, and a fantasy video scene with play icon. Woman's face upper right, no crop on top. Refined cinematic high quality 3D illustration. Fade fully into #03031b on left and bottom edges for seamless native screen composition. No UI, no text, no headlines, no buttons, no balance, no watermark. This must be just reusable artwork matching the reference hero very closely.

## creditCoinsPrompt

Use case: product-mockup. Reusable mobile app credit currency illustration, transparent background. A premium glossy 3D silver/white metal coin pile: one stack of four thick coins behind at right, one shorter stack of two at left front, and one leaning upright coin at front left. Each front/top coin has a raised elegant simple capital M monogram, NOT a crown. Rounded bevel edges, detailed machined coin rims, specular studio highlights, rich silver-white shaded surfaces with neutral grey shadows (no colored lighting). Three-quarter isometric perspective. All coins fully within the image with 10 percent transparent margin on every side. Visually matches luxurious game credit coin packs, suitable to tint purple, cyan, teal or gold at runtime. Real alpha transparency, no opaque backdrop, no captions, no other objects. Square canvas.

## longStoryHomePrompt

Use case: ads-marketing. Wide landscape 2:1 reusable hero illustration for MotionMagic AI Long Story feature. Deep dark navy purple cinematic background, glowing violet-magenta light flowing through three floating portrait film frames on RIGHT HALF: same young adult female fantasy adventurer in three consecutive scenes, first mysterious futuristic city, second discovering luminous ancient temple, third hopeful dawn. Sophisticated cinematic 3D animated-film look, consistent character, beautiful purple and cobalt rim light, little film perforations along the edges of frames, visual storytelling progression. A subtle audio waveform beneath the film frames on right to suggest speaking characters. LEFT 48 percent clean empty dark navy gradient for native headline and button overlay. NO text, no labels, no duration, no UI buttons, no watermark. Vivid refined premium illustration for app card. Match dark purple-magenta-blue vibe of MotionMagic credit shop.
