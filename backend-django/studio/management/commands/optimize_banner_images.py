import io
import time

from django.conf import settings
from django.core.cache import cache
from django.core.management.base import BaseCommand, CommandError
from django.utils import timezone

from studio import banner_media, storage
from studio.models import ShowcaseBanner
from studio.showcase import FEED_CACHE_KEY, upload_media


class Command(BaseCommand):
    help = "Compress existing uploaded Home banners once. Keeps originals and skips bundled or optimized images."

    def handle(self, *args, **options):
        changed = failures = 0
        for banner in ShowcaseBanner.objects.exclude(image_key="").order_by("pk").iterator(chunk_size=50):
            original = banner.image_key
            if original.startswith("builtin:") or banner_media.KEY_MARKER in original:
                continue
            uploaded = None
            s3 = None
            try:
                if not original.startswith(f"catalog/templates/{banner.pk}/banner/") or ".." in original:
                    raise ValueError("Invalid banner storage key")
                s3 = storage.client()
                response = s3.get_object(Bucket=settings.S3_BUCKET, Key=original)
                size = response.get("ContentLength")
                data = bytearray()
                deadline = time.monotonic() + 45
                with response["Body"] as stream:
                    if type(size) is not int or not 0 < size <= banner_media.MAX_UPLOAD:
                        raise ValueError("Invalid banner size")
                    while chunk := stream.read(64 * 1024):
                        data.extend(chunk)
                        if len(data) > size or time.monotonic() > deadline:
                            raise ValueError("Banner download exceeded its limit")
                if len(data) != size:
                    raise ValueError("Incomplete banner download")
                optimized = banner_media.optimize_banner(io.BytesIO(data))
                uploaded = upload_media(banner, "banner", optimized)
                # Do not overwrite an upload made by the owner while this command was processing.
                updated = ShowcaseBanner.objects.filter(pk=banner.pk, image_key=original).update(
                    image_key=uploaded, updated_at=timezone.now())
                if updated:
                    changed += 1
                    cache.delete(FEED_CACHE_KEY)
                    self.stdout.write(f"{banner.pk}: {size:,} → {optimized.size:,} bytes")
                    uploaded = None
                else:
                    self.stdout.write(f"{banner.pk}: owner changed image; kept the newer version")
            except Exception as error:
                failures += 1
                self.stderr.write(f"{banner.pk}: optimization failed ({type(error).__name__}); original kept")
            finally:
                if uploaded and s3:
                    try:
                        s3.delete_object(Bucket=settings.S3_BUCKET, Key=uploaded)
                    except Exception:
                        failures += 1
                        self.stderr.write(f"{banner.pk}: could not remove unused optimized copy")
        self.stdout.write(f"Optimized {changed} banner(s). Original objects retained.")
        if failures:
            raise CommandError(f"{failures} banner(s) could not be optimized; their originals are unchanged.")
