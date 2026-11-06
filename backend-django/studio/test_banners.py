import hashlib
import io
from unittest.mock import patch
from django.contrib.auth.models import User
from django.core.cache import cache
from django.core.files.uploadedfile import SimpleUploadedFile
from django.core.management import call_command
from django.test import Client, TestCase, override_settings
from . import bundled_templates
from .models import ShowcaseBanner, Ledger


@override_settings(OWNER_USERNAME="owner", S3_BUCKET="test-bucket")
class BannerTests(TestCase):
    def setUp(self):
        cache.clear()
        self.owner = User.objects.create_superuser("owner", password="owner-test-password")
        call_command("sync_showcase_templates", stdout=io.StringIO())
        self.item = ShowcaseBanner.objects.first()

    def data(self, **changes):
        return {"title": "Updated banner", "destination": "templates", "position": 9,
                "display_seconds": 7, "published": "on", **changes}

    @patch("studio.showcase.storage.client")
    def test_five_banners_are_ordered_and_initially_four_seconds_without_s3(self, s3):
        rows = self.client.get("/api/v1/templates").json()["banners"]
        self.assertEqual(len(rows), 5)
        self.assertEqual([row["position"] for row in rows], list(range(5)))
        self.assertTrue(all(row["display_seconds"] == 4 for row in rows))
        for row in bundled_templates.banner_catalogue():
            response = self.client.get("/api/v1/templates/artwork/" + row["file"])
            self.assertEqual(hashlib.sha256(b"".join(response.streaming_content)).hexdigest(), row["sha256"])
        s3.assert_not_called()
        self.assertFalse(Ledger.objects.exists())

    def test_owner_edit_changes_order_timing_and_feed_without_reset_on_import(self):
        self.client.get("/api/v1/templates")
        self.client.force_login(self.owner)
        response = self.client.post(f"/owner/showcase/banners/{self.item.pk}/", self.data())
        self.assertEqual(response.status_code, 302)
        call_command("sync_showcase_templates", stdout=io.StringIO())
        rows = self.client.get("/api/v1/templates").json()["banners"]
        self.assertEqual((rows[-1]["id"], rows[-1]["title"], rows[-1]["display_seconds"]), (str(self.item.pk), "Updated banner", 7))
        self.assertEqual(ShowcaseBanner.objects.count(), 5)
        self.assertContains(self.client.get("/owner/showcase/banners/"), "Updated banner")
        mirror = self.client.get("/owner/showcase/")
        self.assertContains(mirror, "Manage Home banners")
        self.assertContains(mirror, 'class="banner-slide"', count=5)
        self.assertEqual(mirror.context["banner_cycle"], 23)
        self.assertEqual(mirror.context["banners"][-1]["preview_end"], "100.000000")
        self.assertNotContains(mirror, "first slide")
        self.assertNotContains(mirror, "<script")

    def test_unpublishing_does_not_restore_a_default_banner(self):
        self.client.force_login(self.owner)
        self.client.post(f"/owner/showcase/banners/{self.item.pk}/", self.data(published=""))
        call_command("sync_showcase_templates", stdout=io.StringIO())
        rows = self.client.get("/api/v1/templates").json()["banners"]
        self.assertEqual(len(rows), 4)
        self.assertNotIn(str(self.item.pk), [row["id"] for row in rows])
        for remaining in ShowcaseBanner.objects.filter(published=True):
            self.client.post(f"/owner/showcase/banners/{remaining.pk}/", self.data(published=""))
        call_command("sync_showcase_templates", stdout=io.StringIO())
        self.assertEqual(self.client.get("/api/v1/templates").json()["banners"], [])
        self.assertContains(self.client.get("/owner/showcase/"), "No published Home banners")

    def test_owner_permission_csrf_and_duration_limits(self):
        self.assertEqual(self.client.get("/owner/showcase/banners/").status_code, 302)
        staff = User.objects.create_user("staff", is_staff=True)
        self.client.force_login(staff)
        self.assertEqual(self.client.get("/owner/showcase/banners/").status_code, 403)
        self.client.force_login(self.owner)
        for seconds in (0, 2, 16, "bad"):
            self.assertEqual(self.client.post(f"/owner/showcase/banners/{self.item.pk}/", self.data(display_seconds=seconds)).status_code, 200)
        self.item.refresh_from_db()
        self.assertEqual(self.item.display_seconds, 4)
        csrf = Client(enforce_csrf_checks=True); csrf.force_login(self.owner)
        self.assertEqual(csrf.post(f"/owner/showcase/banners/{self.item.pk}/", self.data()).status_code, 403)

    @patch("studio.showcase.storage.client")
    def test_replacement_artwork_updates_url_and_removes_bundled_override(self, s3):
        s3.return_value.generate_presigned_url.side_effect = lambda _, Params, ExpiresIn: "https://test-bucket.s3.amazonaws.com/" + Params["Key"]
        self.client.force_login(self.owner)
        self.client.get("/api/v1/templates")
        data = self.data(image=SimpleUploadedFile("new.jpg", b"\xff\xd8\xff" + b"x" * 80, content_type="image/jpeg"))
        self.assertEqual(self.client.post(f"/owner/showcase/banners/{self.item.pk}/", data).status_code, 302)
        row = next(row for row in self.client.get("/api/v1/templates").json()["banners"] if row["id"] == str(self.item.pk))
        self.assertIsNone(row["bundled_image"])
        self.assertIn("/banner/", row["image_url"])
        s3.return_value.upload_fileobj.assert_called_once()
