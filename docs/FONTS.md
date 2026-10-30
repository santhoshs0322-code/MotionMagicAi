# MotionMagic typography

The native app uses Plus Jakarta Sans by Tokotype, distributed through the [Google Fonts repository](https://github.com/google/fonts/tree/main/ofl/plusjakartasans). The font is bundled in the APK, so it does not depend on a font provider, network connection or the phone's selected system font.

Four static weights are stored in `app/src/main/res/font/`: Regular (400), Medium (500), Semibold (600) and Bold (700). They were instantiated from the upstream variable font using fontTools 4.61.1 with `instantiateVariableFont`, `inplace=False` and `updateFontNames=True`. The source axis is `wght` (200–800). Total uncompressed font resource size is 386,236 bytes. The font includes the rupee sign; characters outside its coverage use Android's normal fallback fonts.

All fifteen Material typography roles use the bundled family in `ui/Design.kt`, with dedicated heading, body and label spacing. Existing screen-specific text sizes and weights inherit that family. Text baked into supplied sample images is part of the artwork.

Source font: [PlusJakartaSans[wght].ttf](https://raw.githubusercontent.com/google/fonts/main/ofl/plusjakartasans/PlusJakartaSans%5Bwght%5D.ttf).

Source SHA-256: `89b3fb38aa0d275d7a731d0d817a4f1622b316b4d7fbdedcf02ee9099ff68bc8`.

The SIL Open Font License 1.1 and copyright notice are included in the APK at `assets/licenses/plus_jakarta_sans_OFL.txt` and in the [source license file](../app/src/main/assets/licenses/plus_jakarta_sans_OFL.txt). The font source and generated weights remain under that license.
