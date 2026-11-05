import io
import json
import tempfile
import uuid
from pathlib import Path
from unittest.mock import patch

from django.core.management import call_command
from django.test import TestCase, SimpleTestCase, override_settings
from rest_framework.test import APIClient

from . import fal, fal_catalog, reference_media, services
from .authentication import issue_token
from .models import Account, Asset, Job, Ledger, Wallet
from .test_fal import RUNTIME, Response


@override_settings(**{**RUNTIME, "MARKUP_BPS": 16667, "STORE_FEE_BPS": 1500})
class VideoReferenceTests(TestCase):
    def setUp(self):
        call_command("sync_provider_rates", stdout=io.StringIO())
        self.account = Account.objects.create(google_sub="references", email="refs@example.test", name="Refs")
        Wallet.objects.create(account=self.account, balance_paise=10000)
        self.video = self.asset("video/mp4")
        self.photos = [self.asset("image/png") for _ in range(4)]

    def asset(self, mime, account=None, **values):
        owner = account or self.account
        return Asset.objects.create(account=owner, key=f"accounts/{owner.pk}/video/uploaded/{uuid.uuid4()}",
                                    mime=mime, size=4, ready=True, **values)

    def quote(self, **changes):
        data = dict(service="kling-o3-edit-1080p-silent", prompt="Replace the subject in @Video1 using @Image1",
                    duration=10, generated_audio=False, input_kind="video", reference_image_count=4)
        return services.create_quote(self.account, {**data, **changes})

    def submit(self, quote=None, **changes):
        return services.submit_job(self.account, (quote or self.quote()).pk, "video-refs-key",
                                   str(self.video.pk), changes.get("ids", [str(row.pk) for row in self.photos]))[0]

    def test_quote_is_fast_and_does_not_upload_probe_or_reserve(self):
        with patch("studio.storage.client") as s3, patch("studio.reference_media.billable_seconds") as probe:
            quote = self.quote()
        self.assertEqual(quote.charge_paise, services.price(100))
        self.assertFalse(Ledger.objects.exists())
        s3.assert_not_called(); probe.assert_not_called()

    def test_four_photos_bind_in_order_and_same_request_only_reserves_once(self):
        quote = self.quote()
        job = self.submit(quote)
        self.assertEqual(services.submit_job(self.account, quote.pk, "video-refs-key")[0].pk, job.pk)
        quote.refresh_from_db()
        self.assertEqual([row["id"] for row in quote.payload["reference_images"]], [str(row.pk) for row in self.photos])
        self.assertEqual(Ledger.objects.filter(kind="reserve").count(), 1)

    def test_foreign_duplicate_missing_and_wrong_type_photos_never_reserve(self):
        other = Account.objects.create(google_sub="foreign-refs", email="other@example.test", name="Other")
        foreign = self.asset("image/png", other)
        for ids in ([str(foreign.pk)] * 4, [str(self.photos[0].pk)] * 4, [], [str(self.video.pk)] * 4,
                    [str(uuid.uuid4()) for _ in range(4)], ["not-a-uuid"] * 4):
            with self.subTest(ids=ids), self.assertRaises(Exception):
                self.submit(ids=ids)
        self.assertFalse(Job.objects.exists()); self.assertFalse(Ledger.objects.exists())

    def test_oversized_deleted_or_unready_photo_never_reserves(self):
        for values in ({"size": 11 * 1024**2}, {"deleted": True}, {"ready": False}):
            Asset.objects.filter(pk=self.photos[0].pk).update(**values)
            with self.assertRaises(Exception): self.submit()
            Asset.objects.filter(pk=self.photos[0].pk).update(size=4, deleted=False, ready=True)
        self.assertFalse(Ledger.objects.exists())

    def test_count_limits_and_unsupported_models_are_rejected(self):
        for count in (-1, 5, True, "4"):
            with self.assertRaises(Exception): self.quote(reference_image_count=count)
        with self.assertRaises(Exception):
            self.quote(service="pixverse-v6-720p-silent", input_kind="text", reference_image_count=1)
        with self.assertRaises(Exception): self.quote(reference_image_ids=[str(row.pk) for row in self.photos] * 2)

    def test_bound_quote_cannot_replace_or_reorder_photos(self):
        ids = [str(row.pk) for row in self.photos]
        quote = self.quote(reference_image_ids=ids)
        with self.assertRaisesMessage(Exception, "differ from your quote"):
            self.submit(quote, ids=list(reversed(ids)))
        self.assertFalse(Ledger.objects.exists())

    def test_active_photo_cannot_be_deleted_and_other_account_cannot_access_it(self):
        self.submit()
        client = APIClient(); client.credentials(HTTP_AUTHORIZATION="Bearer " + issue_token(self.account))
        with patch("studio.storage.client") as s3:
            self.assertEqual(client.delete(f"/api/v1/assets/{self.photos[0].pk}").status_code, 409)
            s3.assert_not_called()
        other = Account.objects.create(google_sub="other-delete", email="delete@example.test", name="Other")
        client.credentials(HTTP_AUTHORIZATION="Bearer " + issue_token(other))
        self.assertEqual(client.get(f"/api/v1/assets/{self.photos[0].pk}").status_code, 404)

    @patch("studio.fal.requests.post")
    @patch("studio.storage.client")
    def test_worker_sends_one_video_and_four_photos_in_one_paid_request(self, s3, post):
        job = self.submit()
        s3.return_value.generate_presigned_url.side_effect = lambda method, Params, **kwargs: "https://signed.test/" + Params["Key"]
        post.return_value = Response({"request_id": "edit-reference-test"}, 202)
        with patch("studio.reference_media.prepare_video", return_value=("https://signed.test/snapshot.mp4", 90)):
            self.assertEqual(fal.submit(job)["state"], "running")
        post.assert_called_once()
        body = post.call_args.kwargs["json"]
        self.assertEqual(body["video_url"], "https://signed.test/snapshot.mp4")
        self.assertEqual(len(body["image_urls"]), 4)
        self.assertNotIn("duration", body); self.assertNotIn("aspect_ratio", body)
        self.assertNotIn("generate_audio", body); self.assertFalse(body["keep_audio"])
        job.refresh_from_db()
        self.assertEqual(fal.terminal(job, {"key": "output"})["cost_paise"], 90)
        output = self.asset("video/mp4")
        services.finish_job(job.pk, "succeeded", 90, output)
        services.finish_job(job.pk, "succeeded", 90, output)
        wallet = Wallet.objects.get(account=self.account)
        self.assertEqual((wallet.balance_paise, wallet.held_paise), (10000 - services.price(90), 0))
        self.assertEqual(Ledger.objects.filter(kind="charge").count(), 1)

    @patch("studio.fal.requests.post")
    def test_invalid_video_stops_before_paid_call_and_releases_hold(self, post):
        job = self.submit()
        with patch("studio.reference_media.prepare_video", side_effect=ValueError("too long")):
            result = fal.submit(job)
        self.assertEqual((result["state"], result["cost_paise"], result["error_code"]), ("failed", 0, "invalid_reference"))
        post.assert_not_called()
        services.finish_job(job.pk, "failed", 0)
        self.assertEqual(Wallet.objects.get(account=self.account).held_paise, 0)

    @patch("studio.storage.client")
    def test_exact_checked_video_is_snapshotted_without_buffering_whole_file(self, s3):
        job = self.submit()
        stream = io.BytesIO(b"clip")
        s3.return_value.get_object.return_value = {"Body": stream}
        s3.return_value.generate_presigned_url.return_value = "https://signed.test/snapshot"
        copied = []
        s3.return_value.upload_file.side_effect = lambda path, bucket, key, **kwargs: copied.append((Path(path).read_bytes(), key))
        with patch("studio.reference_media.billable_seconds", return_value=5):
            url, cost = reference_media.prepare_video(job, self.video)
        self.assertEqual((url, cost), ("https://signed.test/snapshot", 50))
        self.assertEqual(copied, [(b"clip", f"accounts/{self.account.pk}/video/job-inputs/{job.pk}/source.mp4")])
        self.assertTrue(stream.closed)

    @patch("studio.storage.client")
    def test_underquoted_length_or_overwritten_upload_never_reaches_snapshot(self, s3):
        job = self.submit()
        for content, seconds in ((b"clip", 11), (b"longer than declared", 5), (b"x", 5)):
            s3.return_value.get_object.return_value = {"Body": io.BytesIO(content)}
            with patch("studio.reference_media.billable_seconds", return_value=seconds), self.assertRaises(ValueError):
                reference_media.prepare_video(job, self.video)
        s3.return_value.upload_file.assert_not_called()

    def test_credit_calculation_retains_margin_fee_and_single_total_rounding(self):
        self.assertEqual(services.price(17 * 10), 534)
        self.assertEqual(services.price(17 * 5), 267)
        no_photos = self.quote(reference_image_count=0, prompt="Restyle @Video1")
        self.assertEqual(no_photos.charge_paise, self.quote().charge_paise)

    def test_prompt_cannot_refer_to_a_missing_reference_photo(self):
        for prompt, count in (("Use @Image1", 0), ("Use @Image4", 3), ("Use @Image0", 4)):
            with self.assertRaisesMessage(Exception, "Add that reference photo or update the prompt"):
                self.quote(prompt=prompt, reference_image_count=count)
        self.assertFalse(Ledger.objects.exists())


class VideoProbeTests(SimpleTestCase):
    def probe(self, duration="4.21", width=1280, height=720):
        def run(*args, **kwargs):
            self.assertIn("file,pipe", args[0])
            self.assertEqual(kwargs["timeout"], 30)
            kwargs["stdout"].write(json.dumps({"format": {"duration": duration}, "streams": [{"width": width, "height": height}]}).encode())
        with tempfile.TemporaryDirectory() as folder, patch("studio.reference_media.subprocess.run", side_effect=run):
            return reference_media.billable_seconds(Path(folder) / "source.mp4")

    def test_fractional_seconds_round_up_and_invalid_duration_or_dimensions_fail(self):
        self.assertEqual(self.probe(), 5)
        self.assertEqual(self.probe("10"), 10)
        for value in ("2.9", "15.01", "NaN", "Infinity", "invalid"):
            with self.subTest(value=value), self.assertRaises(ValueError): self.probe(value)
        with self.assertRaises(ValueError): self.probe(width=640)

    def test_new_scene_contract_keeps_output_settings_and_includes_photos(self):
        config = next(config for slug, _, config, _ in fal_catalog.variants() if slug == "kling-o3-reference-1080p-audio")
        endpoint, body = fal_catalog.request(dict(model="fal:kling-o3-reference", config=config, aspect_ratio="9:16",
            prompt="Next scene @Video1, @Image1", units=10, generated_audio=True), "https://signed.test/clip", ["https://signed.test/photo"])
        self.assertTrue(endpoint.endswith("/reference"))
        self.assertEqual((body["duration"], body["aspect_ratio"], body["keep_audio"], body["image_urls"]), ("10", "9:16", True, ["https://signed.test/photo"]))
