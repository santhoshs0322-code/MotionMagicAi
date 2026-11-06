import io
import os
from unittest.mock import patch

from django import forms
from django.core.management import call_command
from django.core.management.base import CommandError
from django.test import SimpleTestCase, TestCase, override_settings
from PIL import Image

from . import banner_media
from .models import ShowcaseBanner


def png(size=(900, 300), mode="RGB", color="purple"):
    stream = io.BytesIO()
    Image.new(mode, size, color).save(stream, "PNG")
    return stream.getvalue()


class BannerOptimizationTests(SimpleTestCase):
    def test_large_uploaded_image_becomes_a_bounded_webp(self):
        stream = io.BytesIO()
        Image.frombytes("RGB", (1800, 600), os.urandom(1800 * 600 * 3)).save(stream, "PNG")
        self.assertGreater(stream.tell(), 2 * 1024**2)
        optimized = banner_media.optimize_banner(stream)
        self.assertLessEqual(optimized.size, 300 * 1024)
        with Image.open(optimized) as image:
            self.assertEqual(image.format, "WEBP")
            self.assertLessEqual(image.width, 1500)
            self.assertLessEqual(image.height, 500)
            self.assertAlmostEqual(image.width / image.height, 3, places=1)

    def test_transparency_is_preserved_and_small_images_are_not_enlarged(self):
        optimized = banner_media.optimize_banner(io.BytesIO(png((120, 40), "RGBA", (100, 0, 200, 80))))
        with Image.open(optimized) as image:
            self.assertEqual(image.size, (120, 40))
            self.assertIn("A", image.getbands())
            self.assertEqual(image.getpixel((0, 0))[3], 80)

    def test_camera_orientation_is_applied_without_retaining_exif(self):
        source = io.BytesIO()
        exif = Image.Exif()
        exif[274] = 6
        Image.new("RGB", (60, 180), "blue").save(source, "JPEG", exif=exif)
        optimized = banner_media.optimize_banner(source)
        with Image.open(optimized) as image:
            self.assertEqual(image.size, (180, 60))
            self.assertIsNone(image.getexif().get(274))

    def test_corrupted_and_oversized_inputs_are_rejected(self):
        for raw in (b"\xff\xd8\xffbroken", b"x" * (banner_media.MAX_UPLOAD + 1)):
            with self.subTest(size=len(raw)), self.assertRaises(forms.ValidationError):
                banner_media.optimize_banner(io.BytesIO(raw))

    @patch("studio.banner_media.ImageOps.exif_transpose")
    @patch("studio.banner_media.Image.open")
    def test_pixel_limit_is_checked_before_decompressing(self, opened, transpose):
        image = opened.return_value.__enter__.return_value
        image.format, image.width, image.height, image.size = "PNG", 16000, 16000, (16000, 16000)
        with self.assertRaises(forms.ValidationError):
            banner_media.optimize_banner(io.BytesIO(b"header"))
        transpose.assert_not_called()


@override_settings(S3_BUCKET="test-bucket")
class ExistingBannerOptimizationTests(TestCase):
    def setUp(self):
        self.banner = ShowcaseBanner.objects.create(title="Uploaded banner")
        self.original = f"catalog/templates/{self.banner.pk}/banner/original.png"
        self.banner.image_key = self.original
        self.banner.save()

    @patch("studio.storage.client")
    def test_command_optimizes_once_and_keeps_original_objects(self, client):
        image = png()
        client.return_value.get_object.return_value = {"ContentLength": len(image), "Body": io.BytesIO(image)}
        call_command("optimize_banner_images", stdout=io.StringIO())
        self.banner.refresh_from_db()
        self.assertIn(banner_media.KEY_MARKER, self.banner.image_key)
        self.assertTrue(self.banner.image_key.endswith(".webp"))
        call_command("optimize_banner_images", stdout=io.StringIO())
        client.return_value.get_object.assert_called_once()
        client.return_value.delete_object.assert_not_called()
        self.assertEqual(client.return_value.upload_fileobj.call_args.kwargs["ExtraArgs"]["ContentType"], "image/webp")

    @patch("studio.storage.client")
    def test_bad_existing_image_is_not_replaced(self, client):
        client.return_value.get_object.return_value = {"ContentLength": 3, "Body": io.BytesIO(b"bad")}
        with self.assertRaises(CommandError):
            call_command("optimize_banner_images", stdout=io.StringIO(), stderr=io.StringIO())
        self.banner.refresh_from_db()
        self.assertEqual(self.banner.image_key, self.original)
        client.return_value.upload_fileobj.assert_not_called()

    @patch("studio.storage.client")
    def test_owner_upload_during_optimization_wins_and_orphan_is_removed(self, client):
        image = png()
        client.return_value.get_object.return_value = {"ContentLength": len(image), "Body": io.BytesIO(image)}
        replacement = f"catalog/templates/{self.banner.pk}/banner/newer.webp"
        client.return_value.upload_fileobj.side_effect = lambda *args, **kwargs: ShowcaseBanner.objects.filter(pk=self.banner.pk).update(image_key=replacement)
        call_command("optimize_banner_images", stdout=io.StringIO())
        self.banner.refresh_from_db()
        self.assertEqual(self.banner.image_key, replacement)
        removed = client.return_value.delete_object.call_args.kwargs["Key"]
        self.assertIn(banner_media.KEY_MARKER, removed)
        self.assertNotIn(removed, (self.original, replacement))
