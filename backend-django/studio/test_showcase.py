from unittest.mock import patch

from django.contrib.auth.models import User
from django.core.cache import cache
from django.core.files.uploadedfile import SimpleUploadedFile
from django.test import Client, TestCase, override_settings

from .models import Rate, ShowcaseTemplate
from django.core.management import call_command
from . import bundled_templates, fal_catalog, services
from .limits import CREATION_PROMPT_MAX_LENGTH
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
        data = {"title": "Office Cat", "category_action": "new", "new_category": "Trending", "prompt": "A cat at work", "mode": "image_to_video",
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
            "title": "Office Cat", "category_action": "new", "new_category": "Trending", "prompt": "A cat at work", "mode": "image_to_video",
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
                "aspect_ratios": ["9:16", "1:1", "3:4"], "input_modes": ["text", "image_and_text"]})
        call_command("sync_showcase_templates", stdout=io.StringIO())
        self.item = ShowcaseTemplate.objects.order_by("position").first()

    def edit_data(self):
        return dict(title="Mars, my version", category_action="new", new_category="Space", prompt="Keep the uploaded person's face. A blue sunset on Mars.",
            mode="create_image", rate=self.rate.pk, aspect_ratio="9:16", duration=0, requires_reference="on", position=7, published="on")

    def test_owner_category_picker_includes_drafts_and_preselects_current_category(self):
        ShowcaseTemplate.objects.create(title="Private draft", category="Unpublished ideas", prompt="A portrait", mode="create_image", rate=self.rate)
        self.client.force_login(self.owner)
        response = self.client.get(f"/owner/showcase/{self.item.pk}/")
        form = response.context["form"]
        self.assertIn(("Unpublished ideas", "Unpublished ideas"), form.fields["category"].choices)
        self.assertEqual(form["category_action"].value(), "existing")
        self.assertEqual(form["category"].value(), self.item.category)
        html = response.content.decode()
        self.assertLess(html.index('id="category-existing"'), html.index('name="poster"'))
        self.assertLess(html.index('name="poster"'), html.index('name="prompt"'))
        self.assertLess(html.index('name="prompt"'), html.index('name="rate"'))
        self.assertContains(response, "Qwen Image 720p</option>")
        self.assertNotContains(response, "Rate object")
        self.assertNotContains(response, "<script")

    @patch("studio.showcase.storage.client")
    def test_new_category_is_normalized_and_can_be_selected_for_another_template(self, s3):
        self.client.force_login(self.owner)
        self.client.get("/api/v1/templates")  # Warm the public catalogue before editing.
        data = {**self.edit_data(), "new_category": "  Aurora   Stories  "}
        response = self.client.post(f"/owner/showcase/{self.item.pk}/", data)
        self.assertEqual(response.status_code, 302)
        self.item.refresh_from_db()
        self.assertEqual(self.item.category, "Aurora Stories")
        another = ShowcaseTemplate.objects.exclude(pk=self.item.pk).first()
        data.update(category_action="existing", category="Aurora Stories", new_category="")
        self.assertEqual(self.client.post(f"/owner/showcase/{another.pk}/", data).status_code, 302)
        another.refresh_from_db()
        self.assertEqual(another.category, "Aurora Stories")
        feed = self.client.get("/api/v1/templates").json()["templates"]
        self.assertEqual(sum(row["category"] == "Aurora Stories" for row in feed), 2)
        self.assertContains(self.client.get("/owner/showcase/"), "Aurora Stories")
        s3.assert_not_called()

    @patch("studio.showcase.storage.client")
    def test_create_new_reuses_existing_name_ignoring_capitalization(self, s3):
        self.client.force_login(self.owner)
        original = self.item.category
        data = {**self.edit_data(), "new_category": original.swapcase()}
        self.assertEqual(self.client.post(f"/owner/showcase/{self.item.pk}/", data).status_code, 302)
        self.item.refresh_from_db()
        self.assertEqual(self.item.category, original)
        s3.assert_not_called()

    @patch("studio.showcase.storage.client")
    def test_invalid_categories_do_not_save_or_upload_artwork(self, s3):
        self.client.force_login(self.owner)
        original = self.item.category
        cases = [
            {"category_action": "existing", "category": "Not a saved category", "new_category": ""},
            {"category_action": "existing", "category": "", "new_category": ""},
            {"category_action": "new", "new_category": "   "},
            {"category_action": "new", "new_category": "x" * 41},
            {"category_action": "invalid"},
        ]
        for values in cases:
            with self.subTest(values=values):
                poster = SimpleUploadedFile("replacement.jpg", b"\xff\xd8\xff" + b"a" * 80, content_type="image/jpeg")
                response = self.client.post(f"/owner/showcase/{self.item.pk}/", {**self.edit_data(), **values, "poster": poster})
                self.assertEqual(response.status_code, 200)
                self.assertTrue(response.context["form"].errors)
                self.item.refresh_from_db()
                self.assertEqual(self.item.category, original)
        s3.assert_not_called()

    @patch("studio.showcase.storage.client")
    def test_switching_category_choice_ignores_the_inactive_control(self, s3):
        self.client.force_login(self.owner)
        original = self.item.category
        data = {**self.edit_data(), "category_action": "existing", "category": original, "new_category": "x" * 41}
        self.assertEqual(self.client.post(f"/owner/showcase/{self.item.pk}/", data).status_code, 302)
        self.item.refresh_from_db()
        self.assertEqual(self.item.category, original)
        data.update(category_action="new", category="A stale dropdown value", new_category="Fresh category")
        self.assertEqual(self.client.post(f"/owner/showcase/{self.item.pk}/", data).status_code, 302)
        self.item.refresh_from_db()
        self.assertEqual(self.item.category, "Fresh category")
        s3.assert_not_called()

    @patch("studio.showcase.storage.client")
    def test_thirty_image_templates_publish_without_sample_video_or_s3_downloads(self, s3):
        rows = self.client.get("/api/v1/templates")
        self.assertEqual(rows["Cache-Control"], "public, max-age=30")
        feed = rows.json()["templates"]
        self.assertEqual(len(feed), 30)
        self.assertEqual(feed[0]["title"], "Mars Astronaut")
        self.assertEqual(feed[10]["title"], "Lightning Hero — Man")
        self.assertEqual(feed[-1]["title"], "Forest Archer — Woman")
        prefix = (
            "Use the uploaded photo as the identity reference. Preserve the person’s recognizable facial features, "
            "skin tone, hairstyle, and approximate age as closely as possible. Apply the scene and style described below. "
            "For children, use age-appropriate clothing and styling. Vertical 3:4 composition, clear unobstructed face, "
            "no text, logos, buttons, or watermark."
        )
        for row in feed[10:]:
            self.assertTrue(row["prompt"].startswith(prefix + " "))
            self.assertGreater(len(row["prompt"]), 500)
            self.assertLessEqual(len(row["prompt"]), CREATION_PROMPT_MAX_LENGTH)
            self.assertEqual(row["aspect_ratio"], "3:4")
            self.assertEqual(row["prompt"], ShowcaseTemplate.objects.get(pk=row["id"]).prompt)
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
        self.assertEqual(ShowcaseTemplate.objects.count(), 30)
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
        self.assertEqual(len(rows), 29)
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
        template = ShowcaseTemplate.objects.get(title="Lightning Hero — Man")
        data = dict(service=self.rate.pk, prompt=template.prompt, input_kind="image", aspect_ratio="3:4", asset_id=str(photo.pk))
        normal = services.create_quote(account, data)
        quote = services.create_quote(account, {**data, "template_id": str(template.pk)})
        job, _ = services.submit_job(account, quote.pk, "owned-template-test")
        wallet.refresh_from_db()
        self.assertEqual(quote.charge_paise, normal.charge_paise)
        self.assertEqual(job.quote.payload["asset_id"], str(photo.pk))
        endpoint, body = fal_catalog.request(job.quote.payload, "https://example.test/owned-photo.png")
        self.assertEqual(endpoint, "fal-ai/qwen-image-edit")
        self.assertEqual(body["prompt"], template.prompt)
        self.assertEqual(body["image_url"], "https://example.test/owned-photo.png")
        self.assertEqual(job.quote.payload["aspect_ratio"], "3:4")
        self.assertEqual((wallet.balance_paise, wallet.held_paise), (200, quote.charge_paise))

    def test_prompt_limit_rejects_oversized_input_without_reserving_credits(self):
        account = Account.objects.create(google_sub="long-template", email="long@example.test", name="User")
        wallet = Wallet.objects.create(account=account, balance_paise=200)
        data = dict(service=self.rate.pk, prompt="a" * CREATION_PROMPT_MAX_LENGTH, input_kind="text", aspect_ratio="3:4")
        self.assertEqual(services.create_quote(account, data).payload["prompt"], data["prompt"])
        with self.assertRaisesMessage(services.Problem, "within the service limit"):
            services.create_quote(account, {**data, "prompt": data["prompt"] + "a"})
        wallet.refresh_from_db()
        self.assertEqual(wallet.held_paise, 0)
        self.assertFalse(Ledger.objects.exists())

    @patch("studio.showcase.storage.client")
    def test_owner_can_edit_long_identity_prompt_and_sync_preserves_it(self, s3):
        template = ShowcaseTemplate.objects.get(title="Forest Archer — Woman")
        self.client.force_login(self.owner)
        self.client.get("/api/v1/templates")
        prompt = template.prompt + " Add soft morning light."
        data = {**self.edit_data(), "title": template.title, "prompt": prompt, "aspect_ratio": "3:4"}
        response = self.client.post(f"/owner/showcase/{template.pk}/", data)
        self.assertEqual(response.status_code, 302, response.content[:500])
        call_command("sync_showcase_templates", stdout=io.StringIO())
        current = next(row for row in self.client.get("/api/v1/templates").json()["templates"] if row["id"] == str(template.pk))
        self.assertEqual(current["prompt"], prompt)
        self.assertEqual(current["aspect_ratio"], "3:4")
        self.assertEqual(ShowcaseTemplate.objects.count(), 30)
        self.assertContains(self.client.get(f"/owner/showcase/{template.pk}/"), "Add soft morning light.")
        self.client.post(f"/owner/showcase/{template.pk}/unpublish/")
        call_command("sync_showcase_templates", stdout=io.StringIO())
        self.assertNotIn(str(template.pk), [row["id"] for row in self.client.get("/api/v1/templates").json()["templates"]])
        s3.assert_not_called()
