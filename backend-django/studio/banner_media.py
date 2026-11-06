"""Small, bounded public banner derivatives; never processes customer generation assets."""
import io
import warnings

from django import forms
from django.core.files.base import ContentFile
from PIL import Image, ImageOps, UnidentifiedImageError

MAX_UPLOAD = 5 * 1024**2
MAX_OUTPUT = 300 * 1024
MAX_PIXELS = 24_000_000
KEY_MARKER = "/banner/optimized-v1-"


def optimize_banner(file):
    try:
        file.seek(0)
        raw = file.read(MAX_UPLOAD + 1)
        file.seek(0)
        if not 0 < len(raw) <= MAX_UPLOAD:
            raise forms.ValidationError("Choose an image under 5 MB.")
        with warnings.catch_warnings():
            warnings.simplefilter("error", Image.DecompressionBombWarning)
            with Image.open(io.BytesIO(raw)) as source:
                if source.format not in ("JPEG", "PNG", "WEBP"):
                    raise forms.ValidationError("Use a JPG, PNG or WebP image.")
                if source.width * source.height > MAX_PIXELS or max(source.size) > 16000:
                    raise forms.ValidationError("Choose a banner under 24 megapixels and 16,000 pixels per side.")
                if getattr(source, "n_frames", 1) != 1:
                    raise forms.ValidationError("Use a still image; banners rotate automatically.")
                image = ImageOps.exif_transpose(source)
                image.thumbnail((1500, 500), Image.Resampling.LANCZOS)
                converted = image.convert("RGBA" if "A" in image.getbands() or "transparency" in source.info else "RGB")
                image.close()
        try:
            for scale in range(4):
                for quality in (88, 78, 68, 58):
                    output = io.BytesIO()
                    converted.save(output, format="WEBP", quality=quality, method=4)
                    data = output.getvalue()
                    if len(data) <= MAX_OUTPUT:
                        return ContentFile(data, name="banner.webp")
                converted.thumbnail((int(converted.width * .8), int(converted.height * .8)), Image.Resampling.LANCZOS)
        finally:
            converted.close()
        raise forms.ValidationError("This image could not be optimized. Choose a simpler banner.")
    except (UnidentifiedImageError, OSError, ValueError, Image.DecompressionBombError, Image.DecompressionBombWarning) as error:
        raise forms.ValidationError("Upload a valid, readable JPG, PNG or WebP banner.") from error
