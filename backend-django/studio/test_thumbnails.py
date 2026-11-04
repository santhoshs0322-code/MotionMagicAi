import io
from datetime import timedelta
from pathlib import Path
from unittest.mock import Mock, patch

from django.test import TestCase, override_settings
from django.utils import timezone
from rest_framework.test import APIClient

from . import services, storage, thumbnails, worker
from .authentication import issue_token
from .models import Account, Asset, Rate, Wallet, Quote, Budget, Job

JPEG = b"\xff\xd8\xff" + b"fixture" + b"\xff\xd9"


@override_settings(S3_BUCKET="test-bucket", FAL_ONLY=False, GATEWAY_URL="https://gateway.example.test", GATEWAY_TOKEN="test",
                   DAILY_PROVIDER_BUDGET_PAISE=100_000, MARKUP_BPS=0, STORE_FEE_BPS=0)
class ThumbnailTests(TestCase):
    def setUp(self):
        self.account = Account.objects.create(google_sub="preview-owner", email="owner@example.test", name="Owner")
        self.other = Account.objects.create(google_sub="preview-other", email="other@example.test", name="Other")
        self.asset = Asset.objects.create(account=self.account, key=f"accounts/{self.account.pk}/image/generated/result/generated.png",
                                          mime="image/png", size=5, ready=True, thumbnail_state="pending")
        self.s3 = Mock()
        self.s3.get_object.return_value = {"Body": io.BytesIO(b"image")}
        self.s3.generate_presigned_url.return_value = "https://test-bucket.s3.amazonaws.com/private-preview?signature=test"
        self.client = APIClient()
        self.client.credentials(HTTP_AUTHORIZATION="Bearer " + issue_token(self.account))

    def render(self, source, destination):
        self.assertEqual(source.read_bytes(), b"image")
        self.rendered_paths = (source, destination)
        destination.write_bytes(JPEG)

    def test_worker_persists_a_small_private_thumbnail_and_cleans_temporary_files(self):
        with patch("studio.storage.client", return_value=self.s3), patch("studio.thumbnails.render", side_effect=self.render):
            self.assertTrue(thumbnails.generate(self.asset.pk))
            self.assertFalse(thumbnails.generate(self.asset.pk))
        self.asset.refresh_from_db()
        self.assertEqual(self.asset.thumbnail_state, "ready")
        self.assertEqual(self.asset.thumbnail_key, self.asset.key + ".thumbnail.jpg")
        self.assertEqual(self.asset.thumbnail_size, len(JPEG))
        self.assertTrue(all(not path.exists() for path in self.rendered_paths))
        self.s3.put_object.assert_called_once()
        self.assertEqual(self.s3.put_object.call_args.kwargs["ContentType"], "image/jpeg")

    def test_preview_url_is_account_scoped_and_never_renders_during_a_get(self):
        self.asset.thumbnail_state = "ready"
        self.asset.thumbnail_key = self.asset.key + ".thumbnail.jpg"
        self.asset.thumbnail_size = len(JPEG)
        self.asset.save()
        url = f"/api/v1/assets/{self.asset.pk}/thumbnail"
        with patch("studio.storage.client", return_value=self.s3), patch("studio.thumbnails.render") as render:
            self.assertEqual(self.client.get(url).status_code, 200)
            self.client.credentials(HTTP_AUTHORIZATION="Bearer " + issue_token(self.other))
            self.assertEqual(self.client.get(url).status_code, 404)
            render.assert_not_called()
        self.s3.get_object.assert_not_called()

    def test_wrong_thumbnail_key_and_unready_outputs_are_never_signed(self):
        self.asset.thumbnail_state = "ready"
        self.asset.thumbnail_key = f"accounts/{self.other.pk}/private.jpg"
        self.asset.thumbnail_size = len(JPEG)
        self.asset.save()
        with patch("studio.storage.client", return_value=self.s3):
            self.assertEqual(self.client.get(f"/api/v1/assets/{self.asset.pk}/thumbnail").status_code, 409)
        self.s3.generate_presigned_url.assert_not_called()

    def test_history_returns_result_previews_without_fetching_media_or_polling_providers(self):
        self.asset.thumbnail_state = "ready"
        self.asset.thumbnail_key = self.asset.key + ".thumbnail.jpg"
        self.asset.thumbnail_size = len(JPEG)
        self.asset.save()
        rate = Rate.objects.create(slug="preview-history", title="Image", kind="image", provider_model="gateway/image", unit_cost_paise=1)
        quote = Quote.objects.create(account=self.account, rate=rate, payload={"prompt": "Image result", "kind": "image"},
                                     payload_hash="preview", provider_cost_paise=1, overhead_paise=0, markup_bps=0,
                                     fee_bps=0, charge_paise=2, expires_at=timezone.now())
        budget = Budget.objects.create(day=timezone.now().date(), shard=0)
        job = Job.objects.create(account=self.account, quote=quote, budget=budget, output=self.asset,
                                 state="succeeded", idempotency_key="preview-history", next_attempt_at=timezone.now())
        with patch("studio.storage.client", return_value=self.s3) as signer, patch("studio.provider.reconcile") as reconcile:
            response = self.client.get("/api/v1/jobs")
            self.assertEqual(response.status_code, 200)
            result = response.json()["results"][0]
            self.assertEqual(result["id"], str(job.pk))
            self.assertEqual(result["thumbnail_url"], self.s3.generate_presigned_url.return_value)
            self.assertEqual(result["thumbnail_state"], "ready")
            signer.assert_called_once()
            reconcile.assert_not_called()
        self.s3.head_object.assert_not_called()
        self.s3.get_object.assert_not_called()

    def test_expired_thumbnail_lease_can_be_recovered(self):
        self.asset.thumbnail_state = "processing"
        self.asset.thumbnail_lease_until = timezone.now() - timedelta(seconds=1)
        self.asset.save()
        with patch("studio.storage.client", return_value=self.s3), patch("studio.thumbnails.render", side_effect=self.render):
            thumbnails.generate(self.asset.pk)
        self.asset.refresh_from_db()
        self.assertEqual(self.asset.thumbnail_state, "ready")

    def test_thumbnail_failure_does_not_repeat_or_fail_a_paid_generation(self):
        Wallet.objects.create(account=self.account, balance_paise=1_000)
        rate = Rate.objects.create(slug="thumbnail-image", title="Image", kind="image", enabled=True, provider_model="gateway/image", unit_cost_paise=10, max_units=1)
        quote = services.create_quote(self.account, {"service": rate.pk, "prompt": "A landscape"})
        job, _ = services.submit_job(self.account, quote.pk, "thumbnail-success-request")
        self.s3.head_object.return_value = {"ContentLength": 5, "ContentType": "image/png"}
        result = {"state": "succeeded", "cost_paise": 10, "currency": "INR", "output": {"key": storage.generated_key(job), "mime": "image/png", "size": 5}}
        with patch("studio.provider.submit", return_value=result) as submit, patch("studio.storage.client", return_value=self.s3), patch("studio.thumbnails.render", side_effect=ValueError):
            worker.run_one()
            job.refresh_from_db()
            self.assertEqual(job.state, "succeeded")
            self.assertEqual(job.output.thumbnail_state, "failed")
            self.assertEqual(job.charged_paise, quote.charge_paise)
            submit.assert_called_once()
        self.assertTrue(job.output.ready)

    def test_transient_preview_error_is_retried_later_without_restarting_generation(self):
        with patch("studio.storage.client", return_value=self.s3), patch("studio.thumbnails.render", side_effect=TimeoutError):
            thumbnails.generate(self.asset.pk)
            self.assertFalse(thumbnails.generate(self.asset.pk))
        self.asset.refresh_from_db()
        self.assertEqual((self.asset.thumbnail_state, self.asset.thumbnail_attempts), ("pending", 1))
        self.asset.thumbnail_lease_until = timezone.now() - timedelta(seconds=1)
        self.asset.save()
        self.s3.get_object.return_value = {"Body": io.BytesIO(b"image")}
        with patch("studio.storage.client", return_value=self.s3), patch("studio.thumbnails.render", side_effect=self.render):
            thumbnails.generate(self.asset.pk)
        self.asset.refresh_from_db()
        self.assertEqual((self.asset.thumbnail_state, self.asset.thumbnail_attempts), ("ready", 2))

    @patch("studio.thumbnails.subprocess.run")
    def test_decoder_has_a_deadline_one_thread_and_no_network_protocols(self, run):
        thumbnails.render(Path("source.mp4"), Path("preview.jpg"))
        arguments = run.call_args.args[0]
        self.assertEqual(run.call_args.kwargs["timeout"], 30)
        self.assertEqual(arguments[arguments.index("-protocol_whitelist") + 1], "file,pipe")
        self.assertEqual(arguments[arguments.index("-threads") + 1], "1")
        self.assertIn("scale=480:480:force_original_aspect_ratio=decrease:force_divisible_by=2", arguments)
