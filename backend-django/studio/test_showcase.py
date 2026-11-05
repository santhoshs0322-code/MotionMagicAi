from unittest.mock import patch

from django.contrib.auth.models import User
from django.core.cache import cache
from django.core.files.uploadedfile import SimpleUploadedFile
from django.test import Client, TestCase, override_settings

from .models import Rate, ShowcaseTemplate
from django.core.management import call_command
from . import bundled_templates, services
from .models import Account, Asset, Wallet, Ledger
import hashlib
import io


@override_settings(OWNER_USERNAME="owner", S3_BUCKET="test-bucket")
class ShowcaseTests(TestCase):
    def setUp(self):
        cache.clear()
        self.owner = User.objects.create_superuser("owner", password="owner-test-password")
        self.rate = Rate.objects.create(slug="pixverse-demo", title="PixVerse 720p", kind="video",
            provider_model="fal:pixverse-v6", unit_cost_paise=20, enabled=True,
            config={"app_model_id": "pixverse-v6", "resolution": "720p", "durations": [5, 10],
                    "aspect_ratios": ["9:16", "16:9"], "generated_audio": False})

    def files(self):
        return {
            "poster": SimpleUploadedFile("poster.jpg", b"\xff\xd8\xff" + b"a" * 80, content_type="image/jpeg"),
            "sample_video": SimpleUploadedFile("sample.mp4", b"\x00\x00\x00\x18ftypisom" + b"a" * 80, content_type="video/mp4"),
        }

    @patch("studio.showcase.storage.client")
    def test_owner_publish_feed_and_unpublish(self, s3):
        s3.return_value.generate_presigned_url.side_effect = lambda _, Params, ExpiresIn: "https://test-bucket.s3.amazonaws.com/" + Params["Key"]
        self.assertEqual(self.client.get("/api/v1/templates").json()["templates"], [])
        self.assertEqual(self.client.get("/owner/showcase/").status_code, 302)
        staff = User.objects.create_user("staff", password="test", is_staff=True)
        self.client.force_login(staff)
        self.assertEqual(self.client.get("/owner/showcase/").status_code, 403)
        self.client.force_login(self.owner)
        data = {"title": "Office Cat", "category": "Trending", "prompt": "A cat at work", "mode": "image_to_video",
                "rate": self.rate.pk, "aspect_ratio": "9:16", "duration": 5, "position": 0, "featured": "on", "published": "on"}
        response = self.client.post("/owner/showcase/", {**data, **self.files()})
        self.assertEqual(response.status_code, 302, response.content[:500])
        item = ShowcaseTemplate.objects.get()
        self.assertTrue(item.poster_key.startswith(f"catalog/templates/{item.pk}/poster/"))
        self.assertEqual(s3.return_value.upload_fileobj.call_count, 2)
        feed = self.client.get("/api/v1/templates").json()["templates"]
        self.assertEqual(len(feed), 1)
        self.assertEqual(feed[0]["model_id"], "pixverse-v6")
        self.assertEqual(feed[0]["duration"], 5)
        self.assertIn("sample_video", feed[0]["video_url"])
        self.client.post(f"/owner/showcase/{item.pk}/unpublish/")
        self.assertEqual(self.client.get("/api/v1/templates").json()["templates"], [])

    @patch("studio.showcase.storage.client")
    def test_invalid_video_never_publishes_or_uploads(self, s3):
        self.client.force_login(self.owner)
        files = self.files()
        files["sample_video"] = SimpleUploadedFile("bad.mp4", b"not an mp4", content_type="video/mp4")
        response = self.client.post("/owner/showcase/", {
            "title": "Office Cat", "category": "Trending", "prompt": "A cat at work", "mode": "image_to_video",
            "rate": self.rate.pk, "aspect_ratio": "9:16", "duration": 5, "position": 0, "published": "on", **files})
        self.assertEqual(response.status_code, 200)
        self.assertContains(response, "Use an MP4 video")
        self.assertFalse(ShowcaseTemplate.objects.exists())
        s3.return_value.upload_fileobj.assert_not_called()

    def test_showcase_requires_csrf_for_owner_writes(self):
        client = Client(enforce_csrf_checks=True)
        client.force_login(self.owner)
        self.assertEqual(client.post("/owner/showcase/", {}).status_code, 403)


@override_settings(OWNER_USERNAME="owner", S3_BUCKET="test-bucket", FAL_KEY="test-key", DAILY_PROVIDER_BUDGET_PAISE=6400000)
class ImageTemplateTests(TestCase):
    def setUp(self):
        cache.clear()
        self.owner = User.objects.create_superuser("owner", password="owner-test-password")
        self.rate = Rate.objects.create(slug="qwen-image-720p", title="Qwen Image 720p", kind="image", provider_model="fal:qwen-image",
            unit_cost_paise=3, max_units=1, enabled=True, config={"provider": "fal", "app_model_id": "qwen-image", "image_size": "720p",
                "aspect_ratios": ["9:16", "1:1"], "input_modes": ["text", "image_and_text"]})
        call_command("sync_showcase_templates", stdout=io.StringIO())
        self.item = ShowcaseTemplate.objects.order_by("position").first()

    def edit_data(self):
        return dict(title="Mars, my version", category="Space", prompt="Keep the uploaded person's face. A blue sunset on Mars.",
            mode="create_image", rate=self.rate.pk, aspect_ratio="9:16", duration=0, requires_reference="on", position=7, published="on")

    @patch("studio.showcase.storage.client")
    def test_ten_image_templates_publish_without_sample_video_or_s3_downloads(self, s3):
        rows = self.client.get("/api/v1/templates")
        self.assertEqual(rows["Cache-Control"], "public, max-age=30")
        feed = rows.json()["templates"]
        self.assertEqual(len(feed), 10)
        self.assertEqual(feed[0]["title"], "Mars Astronaut")
        self.assertTrue(all(row["mode"] == "create_image" and row["requires_reference"] and not row["video_url"] for row in feed))
        self.assertTrue(all(row["poster_url"].startswith("/api/v1/templates/artwork/") for row in feed))
        s3.assert_not_called()

    @patch("studio.showcase.storage.client")
    def test_owner_edit_invalidates_cached_feed_and_redeploy_preserves_every_change(self, s3):
        self.client.get("/api/v1/templates")
        self.client.force_login(self.owner)
        response = self.client.post(f"/owner/showcase/{self.item.pk}/", self.edit_data())
        self.assertEqual(response.status_code, 302, response.content[:500])
        call_command("sync_showcase_templates", stdout=io.StringIO())
        current = next(row for row in self.client.get("/api/v1/templates").json()["templates"] if row["id"] == str(self.item.pk))
        self.assertEqual((current["title"], current["category"], current["prompt"]), ("Mars, my version", "Space", self.edit_data()["prompt"]))
        self.assertEqual(ShowcaseTemplate.objects.count(), 10)
        response = self.client.get("/owner/showcase/")
        self.assertContains(response, "Mars, my version")
        self.assertContains(response, "Your featured video appears here", count=0)
        self.assertIn("img-src 'self'", response["Content-Security-Policy"])
        s3.assert_not_called()

    @patch("studio.showcase.storage.client")
    def test_replacing_poster_uses_new_media_without_restoring_original_on_sync(self, s3):
        self.client.force_login(self.owner)
        s3.return_value.generate_presigned_url.side_effect = lambda _, Params, ExpiresIn: "https://test-bucket.s3.amazonaws.com/" + Params["Key"]
        data = self.edit_data()
        data["poster"] = SimpleUploadedFile("replacement.jpg", b"\xff\xd8\xff" + b"a" * 80, content_type="image/jpeg")
        self.assertEqual(self.client.post(f"/owner/showcase/{self.item.pk}/", data).status_code, 302)
        call_command("sync_showcase_templates", stdout=io.StringIO())
        row = next(row for row in self.client.get("/api/v1/templates").json()["templates"] if row["id"] == str(self.item.pk))
        self.assertIsNone(row["bundled_poster"])
        self.assertIn("catalog/templates/", row["poster_url"])
        s3.return_value.upload_fileobj.assert_called_once()

    def test_unpublished_template_stays_hidden_after_sync_and_disabled_model_is_hidden(self):
        self.client.force_login(self.owner)
        self.client.get("/api/v1/templates")
        self.client.post(f"/owner/showcase/{self.item.pk}/unpublish/")
        call_command("sync_showcase_templates", stdout=io.StringIO())
        rows = self.client.get("/api/v1/templates").json()["templates"]
        self.assertEqual(len(rows), 9)
        self.assertNotIn(str(self.item.pk), [row["id"] for row in rows])
        self.rate.enabled = False; self.rate.save(); cache.clear()
        self.assertEqual(self.client.get("/api/v1/templates").json()["templates"], [])

    def test_artwork_is_public_cacheable_and_restricted_to_catalogue_files(self):
        row = bundled_templates.catalogue()[0]
        url = "/api/v1/templates/artwork/" + row["file"]
        response = self.client.get(url)
        content = b"".join(response.streaming_content)
        self.assertEqual(hashlib.sha256(content).hexdigest(), row["sha256"])
        self.assertEqual(response["Content-Type"], "image/jpeg")
        self.assertEqual(response["Cache-Control"], "public, max-age=86400")
        self.assertEqual(self.client.get(url, HTTP_IF_NONE_MATCH=response["ETag"]).status_code, 304)
        self.assertEqual(self.client.get("/api/v1/templates/artwork/google-play.json").status_code, 404)
        self.assertEqual(self.client.get("/api/v1/wallet")["Cache-Control"], "no-store")

    def test_missing_or_foreign_user_photo_cannot_start_template_generation(self):
        account = Account.objects.create(google_sub="template-user", email="user@example.test", name="User")
        Wallet.objects.create(account=account, balance_paise=200)
        data = dict(service=self.rate.pk, prompt=self.item.prompt, template_id=str(self.item.pk), input_kind="text", aspect_ratio="9:16")
        with self.assertRaisesMessage(Exception, "Add your own reference photo"):
            services.create_quote(account, data)
        quote = services.create_quote(account, {**data, "input_kind": "image"})
        with self.assertRaisesMessage(Exception, "Upload your reference media"):
            services.submit_job(account, quote.pk, "template-photo-test")
        other = Account.objects.create(google_sub="other-template", email="other@example.test", name="Other")
        photo = Asset.objects.create(account=other, key=f"accounts/{other.pk}/image/uploaded/photo", mime="image/png", size=4, ready=True)
        with self.assertRaisesMessage(Exception, "Source media is unavailable"):
            services.submit_job(account, quote.pk, "template-photo-test", str(photo.pk))
        self.item.published = False; self.item.save()
        with self.assertRaisesMessage(Exception, "no longer available"):
            services.submit_job(account, quote.pk, "template-photo-test", str(photo.pk))
        self.assertFalse(Ledger.objects.exists())

    def test_template_uses_the_owned_photo_and_the_regular_generation_price(self):
        account = Account.objects.create(google_sub="owned-template", email="owned@example.test", name="User")
        wallet = Wallet.objects.create(account=account, balance_paise=200)
        photo = Asset.objects.create(account=account, key=f"accounts/{account.pk}/image/uploaded/photo", mime="image/png", size=4, ready=True)
        data = dict(service=self.rate.pk, prompt=self.item.prompt, input_kind="image", aspect_ratio="9:16", asset_id=str(photo.pk))
        normal = services.create_quote(account, data)
        quote = services.create_quote(account, {**data, "template_id": str(self.item.pk)})
        job, _ = services.submit_job(account, quote.pk, "owned-template-test")
        wallet.refresh_from_db()
        self.assertEqual(quote.charge_paise, normal.charge_paise)
        self.assertEqual(job.quote.payload["asset_id"], str(photo.pk))
        self.assertEqual((wallet.balance_paise, wallet.held_paise), (200, quote.charge_paise))
