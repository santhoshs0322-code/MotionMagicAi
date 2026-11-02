from unittest.mock import patch

from django.contrib.auth.models import User
from django.core.cache import cache
from django.core.files.uploadedfile import SimpleUploadedFile
from django.test import Client, TestCase, override_settings

from .models import Rate, ShowcaseTemplate


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
