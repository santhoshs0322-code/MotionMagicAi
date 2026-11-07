import io
import json
from datetime import timedelta
from unittest.mock import patch, Mock

import requests
from django.core.management import call_command
from django.test import TestCase, SimpleTestCase, override_settings
from django.utils import timezone

from . import fal, fal_catalog, services, worker, storage
from .models import Account, Asset, Wallet, Job, Rate, Ledger, Subscription

RUNTIME = dict(FAL_KEY="test-server-key", GEMINI_API_KEY="", GATEWAY_URL="", GATEWAY_TOKEN="", S3_BUCKET="test-bucket",
               DAILY_PROVIDER_BUDGET_PAISE=6_400_000, FAL_RATE_COSTS={slug: 10 for slug, *_ in fal_catalog.variants()},
               MARKUP_BPS=90_000, STORE_FEE_BPS=0)


class Response:
    def __init__(self, data=None, status=200, content=b"image"):
        self.data, self.status_code = data or {}, status
        self.headers = {"Content-Type": "image/png", "Content-Length": str(len(content))}
        self.raw = io.BytesIO(content)
    def __enter__(self): return self
    def __exit__(self, *_): return False
    def iter_content(self, _): yield json.dumps(self.data).encode()
    def raise_for_status(self):
        if self.status_code >= 400: raise requests.HTTPError()


class ContractTests(SimpleTestCase):
    def test_moderation_uses_typed_errors_not_prompt_or_message_words(self):
        self.assertTrue(fal.content_blocked({"detail": [{"type": "content_policy_violation", "categories": ["model_moderated"]}]}))
        self.assertTrue(fal.content_blocked({"error": {"detail": [{"type": "content_policy_violation"}]}}))
        self.assertFalse(fal.content_blocked({"prompt": "content_policy_violation", "msg": "content_policy_violation", "type": "value_error"}))
        self.assertFalse(fal.content_blocked({"detail": [{"type": "image_too_large"}]}))

    def test_safety_cannot_be_disabled_by_payload_or_rate_configuration(self):
        for slug, model, config, _ in fal_catalog.variants():
            video = "resolution" in config
            sources = ("https://test-bucket.s3.amazonaws.com/source.mp4",) if config.get("reference_video") else (
                None, "https://test-bucket.s3.amazonaws.com/source.jpg")
            for source in sources:
                with self.subTest(slug=slug, source=bool(source)):
                    payload = {"model": "fal:" + config["app_model_id"], "prompt": "A landscape", "aspect_ratio": "9:16",
                               "enable_safety_checker": False, "safety_tolerance": "6",
                               "config": {**config, "enable_safety_checker": False, "safety_tolerance": "6"}}
                    if video:
                        payload.update(units=config["durations"][0], generated_audio=config["generated_audio"])
                    _, body = fal_catalog.request(payload, source)
                    if model["family"] == "nano":
                        self.assertEqual(body["safety_tolerance"], "1")
                        self.assertNotIn("enable_safety_checker", body)  # Not accepted by this endpoint.
                    else:
                        self.assertIs(body["enable_safety_checker"], True)
                        self.assertNotIn("safety_tolerance", body)  # Do not send unsupported knobs.

    def test_every_image_model_has_text_and_reference_contracts_and_one_output(self):
        for slug, model, config, _ in fal_catalog.variants():
            if "resolution" in config: continue
            for ratio in config["aspect_ratios"]:
                for source in (None, "https://test-bucket.s3.amazonaws.com/signed-source"):
                    with self.subTest(slug=slug, ratio=ratio, source=bool(source)):
                        endpoint, body = fal_catalog.request({"model": "fal:" + config["app_model_id"], "config": config, "aspect_ratio": ratio, "prompt": "A landscape"}, source)
                        self.assertEqual(endpoint, model["edit"] if source else model["text"])
                        self.assertEqual(body["num_images"], 1)
                        self.assertFalse(body["sync_mode"])
                        if source:
                            self.assertEqual(body.get("image_url") or body.get("image_urls"), source if model["family"] == "qwen" else [source])
                        size = body.get("image_size")
                        if size and model["family"] in ("gpt", "seedream_pro", "seedream_lite", "seedream_45"):
                            area = size["width"] * size["height"]
                            bounds = {"gpt": (655360, 8294400), "seedream_pro": (921600, 4624220), "seedream_lite": (3686400, 16777216), "seedream_45": (3686400, 16777216)}[model["family"]]
                            self.assertTrue(bounds[0] <= area <= bounds[1])
                            self.assertEqual(size["width"] % 16, 0)
                        if model["family"] == "seedream_45": self.assertNotIn("output_format", body)
                        if model["family"] == "seedream_lite": self.assertEqual(body["sequential_image_generation"], "disabled")

    def test_video_native_audio_and_resolution_map_to_correct_provider_arguments(self):
        for slug, _, config, _ in fal_catalog.variants():
            if "resolution" not in config: continue
            sources = ("https://test-bucket.s3.amazonaws.com/reference.mp4",) if config.get("reference_video") else (None, "https://test-bucket.s3.amazonaws.com/photo")
            for source in sources:
                for duration in config["durations"]:
                    payload = {"model": "fal:" + config["app_model_id"], "config": config, "aspect_ratio": "9:16", "prompt": "A beach", "units": duration, "generated_audio": config["generated_audio"]}
                    endpoint, body = fal_catalog.request(payload, source)
                    self.assertEqual(body.get("image_url"), None if config.get("reference_video") else source)
                    self.assertEqual(body.get("aspect_ratio"), None if config.get("duration_from_source") else "9:16" if config.get("reference_video") or not source else None)
                    if config.get("duration_from_source"):
                        self.assertNotIn("duration", body)
                    if config.get("reference_video"):
                        self.assertEqual(body["video_url"], source)
                        self.assertEqual(body["keep_audio"], config["generated_audio"])
                        self.assertNotIn("generate_audio", body)
                    elif "kling" in endpoint:
                        self.assertIn("/standard/" if config["resolution"] == "720p" else "/pro/", endpoint)
                        self.assertEqual(body["duration"], str(duration))
                        self.assertEqual(body["generate_audio"], config["generated_audio"])
                    else:
                        self.assertEqual(body["duration"], duration)
                        self.assertEqual(body["resolution"], config["resolution"])
                        self.assertEqual(body["generate_audio_switch"], config["generated_audio"])

    def test_media_url_rejects_credentials_local_addresses_redirect_hosts_and_suffix_tricks(self):
        for url in ("http://v3.fal.media/file", "https://fal.media.evil.test/file", "https://127.0.0.1/file", "https://key@v3.fal.media/file", "https://v3.fal.media:444/file", "https://storage.googleapis.com/private/file"):
            with self.subTest(url=url), self.assertRaises(ValueError): fal.media_url(url)
        for url in ("https://v3b.fal.media/file", "https://storage.googleapis.com/falserverless/output.mp4"):
            self.assertEqual(fal.media_url(url), url)

    def test_large_media_reader_is_bounded(self):
        reader = storage.LimitedReader(io.BytesIO(b"x" * 25), 20)
        self.assertEqual(reader.read(8), b"x" * 8)
        self.assertEqual(reader.read(8), b"x" * 8)
        with self.assertRaises(ValueError): reader.read(8)


@override_settings(**RUNTIME)
class FalFlowTests(TestCase):
    def setUp(self):
        call_command("sync_provider_rates", stdout=io.StringIO())
        self.account = Account.objects.create(google_sub="fal-test", email="fal@example.test", name="Fal")
        Subscription.objects.create(account=self.account, product_id="motion_creator_monthly",
                                    token_hash="fal-flow-test-token", token_encrypted="test", plan="creator",
                                    state="active", expires_at=timezone.now() + timedelta(days=1))
        self.wallet = Wallet.objects.create(account=self.account, balance_paise=100_000)

    def job(self, service="qwen-image-720p", **kwargs):
        quote = services.create_quote(self.account, {"service": service, "prompt": "A lake", **kwargs})
        return services.submit_job(self.account, quote.pk, "request-12345")[0]

    def test_catalog_only_advertises_configured_rates_and_revision_invalidates_old_quote(self):
        self.assertEqual(services.enabled_rates().count(), len(RUNTIME["FAL_RATE_COSTS"]) - 1)
        with override_settings(FAL_KEY="", GATEWAY_URL="https://gateway.test", GATEWAY_TOKEN="token"):
            self.assertFalse(services.enabled_rates().exists())
        quote = services.create_quote(self.account, {"service": "qwen-image-720p", "prompt": "A lake"})
        call_command("sync_provider_rates", stdout=io.StringIO())
        self.assertEqual(Rate.objects.get(pk=quote.rate_id).revision, quote.payload["revision"])
        with override_settings(FAL_RATE_COSTS={}): call_command("sync_provider_rates", stdout=io.StringIO())
        with self.assertRaisesMessage(Exception, "Quote expired or service changed"):
            services.submit_job(self.account, quote.pk, "another-key")

    @patch("studio.storage.client")
    def test_new_media_is_grouped_by_account_kind_and_stage(self, s3):
        from . import storage
        s3.return_value.generate_presigned_post.return_value = {"url": "https://s3.test", "fields": {}}
        image_upload, _ = storage.begin_upload(self.account, "image/png", 5, "image")
        video_source, _ = storage.begin_upload(self.account, "image/png", 5, "video")
        self.assertTrue(image_upload.key.startswith(f"accounts/{self.account.pk}/image/uploaded/"))
        self.assertTrue(video_source.key.startswith(f"accounts/{self.account.pk}/video/uploaded/"))
        image_job = self.job()
        self.assertEqual(storage.generated_key(image_job), f"accounts/{self.account.pk}/image/generated/{image_job.pk}/generated.png")
        video_quote = services.create_quote(self.account, {"service": "pixverse-v6-720p-silent", "prompt": "A lake", "duration": 5, "generated_audio": False})
        video_job = services.submit_job(self.account, video_quote.pk, "request-video-12345")[0]
        self.assertEqual(storage.generated_key(video_job, "video/mp4"), f"accounts/{self.account.pk}/video/generated/{video_job.pk}/generated.mp4")
        with self.assertRaisesMessage(Exception, "Choose image or video storage"):
            storage.begin_upload(self.account, "image/png", 5, "other")

    def test_settings_are_frozen_and_silent_price_cannot_buy_native_audio(self):
        job = self.job("kling-3-omni-720p-silent", duration=5, generated_audio=False)
        self.assertEqual(job.quote.charge_paise, 500)
        Rate.objects.filter(pk=job.quote.rate_id).update(config={"resolution": "1080p"})
        endpoint, body = fal_catalog.request(job.quote.payload)
        self.assertIn("/standard/", endpoint)
        self.assertFalse(body["generate_audio"])
        with self.assertRaisesMessage(Exception, "audio setting"):
            services.create_quote(self.account, {"service": "pixverse-v6-720p-silent", "prompt": "A beach", "generated_audio": True})

    def test_each_model_prices_its_full_duration_range_and_rejects_outside_values(self):
        for service, minimum in (("kling-3-omni-720p-silent", 3), ("pixverse-v6-720p-silent", 1)):
            for duration in (minimum, 7, 15):
                quote = services.create_quote(self.account, {"service": service, "prompt": "A lake", "duration": duration, "generated_audio": False})
                self.assertEqual(quote.provider_cost_paise, 10 * duration)
                self.assertEqual(quote.payload["units"], duration)
            for duration in (minimum - 1, 16, 7.5, True):
                with self.assertRaisesMessage(Exception, "supported duration"):
                    services.create_quote(self.account, {"service": service, "prompt": "A lake", "duration": duration, "generated_audio": False})

    def test_other_accounts_and_oversized_photos_are_rejected_before_charging(self):
        other = Account.objects.create(google_sub="other", email="other@example.test", name="Other")
        asset = Asset.objects.create(account=other, key="other/image", mime="image/png", size=100, ready=True)
        with self.assertRaisesMessage(Exception, "Source media is unavailable"):
            services.create_quote(self.account, {"service": "qwen-image-720p", "prompt": "Photo", "asset_id": str(asset.pk)})
        asset.account = self.account; asset.key = f"accounts/{self.account.pk}/uploads/{asset.pk}"; asset.size = 20 * 1024**2; asset.save()
        with self.assertRaisesMessage(Exception, "too large"):
            services.create_quote(self.account, {"service": "qwen-image-720p", "prompt": "Photo", "asset_id": str(asset.pk)})
        self.assertFalse(Ledger.objects.exists())

    def test_story_reference_requires_owned_mp4_and_quotes_video_rate(self):
        service = "kling-o3-reference-1080p-audio"
        with self.assertRaisesMessage(Exception, "Select a reference video"):
            services.create_quote(self.account, {"service": service, "prompt": "Next scene", "duration": 10, "generated_audio": True})
        asset = Asset.objects.create(account=self.account, key=f"accounts/{self.account.pk}/video/uploaded/reference",
                                     mime="video/mp4", size=12_000, ready=True)
        quote = services.create_quote(self.account, {"service": service, "prompt": "Continue @Video1", "duration": 10,
                                                    "generated_audio": True, "asset_id": str(asset.pk)})
        self.assertEqual(quote.payload["input_key"], asset.key)
        self.assertEqual(quote.provider_cost_paise, 100)
        image_asset = Asset.objects.create(account=self.account, key=f"accounts/{self.account.pk}/image/uploaded/photo",
                                           mime="image/png", size=12_000, ready=True)
        with self.assertRaisesMessage(Exception, "MP4 reference video"):
            services.create_quote(self.account, {"service": service, "prompt": "Next scene", "duration": 10,
                                                  "generated_audio": True, "asset_id": str(image_asset.pk)})
        other = Account.objects.create(google_sub="other-story", email="story@example.test", name="Other")
        with self.assertRaisesMessage(Exception, "Source media is unavailable"):
            services.create_quote(other, {"service": service, "prompt": "Next scene", "duration": 10,
                                          "generated_audio": True, "asset_id": str(asset.pk)})

    @patch("studio.storage.client")
    def test_deferred_quote_returns_price_and_balance_without_upload_or_credit_hold(self, s3):
        from rest_framework.test import APIClient
        from .authentication import issue_token
        client = APIClient()
        client.credentials(HTTP_AUTHORIZATION="Bearer " + issue_token(self.account))
        response = client.post("/api/v1/quotes", {"service": "kling-o3-reference-1080p-audio", "prompt": "Next scene",
            "duration": 10, "generated_audio": True, "input_kind": "video"}, format="json")
        self.assertEqual(response.status_code, 200)
        quote = self.account.quote_set.get(pk=response.json()["id"])
        self.assertEqual(quote.payload["input_kind"], "video")
        self.assertNotIn("asset_id", quote.payload)
        self.assertGreater(response.json()["available_credits"], 0)
        self.assertFalse(Asset.objects.exists())
        self.assertFalse(Job.objects.exists())
        self.assertFalse(Ledger.objects.filter(kind="reserve").exists())
        s3.assert_not_called()

    def test_deferred_source_is_validated_before_any_credit_reservation(self):
        quote = services.create_quote(self.account, {"service": "kling-o3-reference-1080p-audio", "prompt": "Next scene",
            "duration": 10, "generated_audio": True, "input_kind": "video"})
        with self.assertRaisesMessage(Exception, "Upload your reference media"):
            services.submit_job(self.account, quote.pk, "missing-source-key")
        other = Account.objects.create(google_sub="deferred-other", email="deferred@example.test", name="Other")
        asset = Asset.objects.create(account=other, key=f"accounts/{other.pk}/video/uploaded/ref",
            mime="video/mp4", size=1000, ready=True)
        with self.assertRaisesMessage(Exception, "Source media is unavailable"):
            services.submit_job(self.account, quote.pk, "foreign-source-key", str(asset.pk))
        asset.account = self.account; asset.key = f"accounts/{self.account.pk}/video/uploaded/ref"; asset.ready = False; asset.save()
        with self.assertRaisesMessage(Exception, "Source media is unavailable"):
            services.submit_job(self.account, quote.pk, "unfinished-source-key", str(asset.pk))
        asset.ready = True; asset.mime = "image/png"; asset.save()
        with self.assertRaisesMessage(Exception, "MP4 reference video"):
            services.submit_job(self.account, quote.pk, "wrong-source-kind-key", str(asset.pk))
        self.assertFalse(Job.objects.exists())
        self.assertFalse(Ledger.objects.filter(kind="reserve").exists())
        quote.refresh_from_db()
        self.assertNotIn("asset_id", quote.payload)

    def test_deferred_job_binds_owned_source_and_retry_does_not_double_charge(self):
        from .authentication import digest
        quote = services.create_quote(self.account, {"service": "qwen-image-720p", "prompt": "A lake", "input_kind": "image"})
        asset = Asset.objects.create(account=self.account, key=f"accounts/{self.account.pk}/image/uploaded/source",
            mime="image/png", size=1000, ready=True)
        job, created = services.submit_job(self.account, quote.pk, "deferred-source-key", str(asset.pk))
        self.assertTrue(created)
        same, created = services.submit_job(self.account, quote.pk, "deferred-source-key", str(asset.pk))
        self.assertFalse(created)
        self.assertEqual(job.pk, same.pk)
        quote.refresh_from_db()
        self.assertEqual(quote.payload["asset_id"], str(asset.pk))
        self.assertEqual(quote.payload["input_key"], asset.key)
        self.assertEqual(quote.payload_hash, digest(json.dumps(quote.payload, sort_keys=True)))
        self.assertEqual(Ledger.objects.filter(kind="reserve").count(), 1)
        with self.assertRaisesMessage(Exception, "already has a job"):
            services.submit_job(self.account, quote.pk, "another-source-key", str(asset.pk))
        text_quote = services.create_quote(self.account, {"service": "qwen-image-720p", "prompt": "A lake", "input_kind": "text"})
        with self.assertRaisesMessage(Exception, "does not include reference media"):
            services.submit_job(self.account, text_quote.pk, "text-source-key", str(asset.pk))

    @patch("studio.fal.requests.post")
    @patch("studio.fal.storage.client")
    def test_story_reference_worker_sends_owned_video_to_fal(self, s3, post):
        asset = Asset.objects.create(account=self.account, key=f"accounts/{self.account.pk}/video/uploaded/reference",
                                     mime="video/mp4", size=12_000, ready=True)
        job = self.job("kling-o3-reference-1080p-audio", duration=10, generated_audio=True, asset_id=str(asset.pk))
        s3.return_value.generate_presigned_url.return_value = "https://test-bucket.s3.amazonaws.com/signed-reference.mp4"
        post.return_value = Response({"request_id": "story-reference-request"}, status=202)
        with patch("studio.reference_media.prepare_video", return_value=("https://test-bucket.s3.amazonaws.com/signed-reference.mp4", job.quote.provider_cost_paise)):
            self.assertEqual(fal.submit(job)["state"], "running")
        args, kwargs = post.call_args
        self.assertIn("/video-to-video/reference", args[0])
        self.assertEqual(kwargs["json"]["video_url"], "https://test-bucket.s3.amazonaws.com/signed-reference.mp4")

    @patch("studio.fal.requests.post")
    @patch("studio.fal.requests.get")
    @patch("studio.fal.storage.existing_generated_image", return_value=None)
    @patch("studio.fal.storage.put_generated_stream")
    @patch("studio.worker.storage.provider_output")
    def test_queue_restart_download_and_settlement_are_idempotent(self, output_asset, put, existing, get, post):
        job = self.job()
        post.return_value = Response({"request_id": "fal-request-123"}, status=202)
        worker.run_one()
        job.refresh_from_db()
        self.assertEqual((job.state, job.provider_id, job.provider_context), ("running", "fal-request-123", {"root": "fal-ai/qwen-image", "endpoint": "fal-ai/qwen-image"}))
        self.assertGreaterEqual((job.next_attempt_at - timezone.now()).total_seconds(), 13)
        self.assertLessEqual((job.next_attempt_at - timezone.now()).total_seconds(), 16)
        self.wallet.refresh_from_db(); self.assertEqual(self.wallet.held_paise, 100)
        asset = Asset.objects.create(account=self.account, key=f"accounts/{self.account.pk}/outputs/{job.pk}/generated.png", mime="image/png", size=5, ready=True)
        output_asset.return_value = asset
        put.return_value = {"key": asset.key, "mime": asset.mime, "size": asset.size}
        get.side_effect = [Response({"status": "COMPLETED"}), Response({"images": [{"url": "https://v3.fal.media/result.png"}]}), Response()]
        Job.objects.filter(pk=job.pk).update(next_attempt_at=timezone.now())
        worker.run_one(); job.refresh_from_db(); self.wallet.refresh_from_db()
        self.assertEqual((job.state, job.output_id, job.charged_paise), ("succeeded", asset.pk, 100))
        self.assertEqual((self.wallet.balance_paise, self.wallet.held_paise), (99_900, 0))
        post.assert_called_once()
        self.assertFalse(worker.run_one())
        self.assertNotIn("headers", get.call_args.kwargs)  # No credentials on media GET.
        self.assertEqual(Ledger.objects.filter(kind="charge").count(), 1)

    def test_success_cannot_settle_without_a_ready_saved_output(self):
        job = self.job()
        with self.assertRaisesMessage(Exception, "Save the generated media"):
            services.finish_job(job.pk, "succeeded", 10)
        incomplete = Asset.objects.create(account=self.account, key=storage.generated_key(job), mime="image/png", size=5, ready=False)
        with self.assertRaisesMessage(Exception, "Generated media does not belong"):
            services.finish_job(job.pk, "succeeded", 10, asset=incomplete)
        job.refresh_from_db(); self.wallet.refresh_from_db()
        self.assertEqual((job.state, job.output_id, self.wallet.balance_paise, self.wallet.held_paise), ("queued", None, 100_000, 100))
        self.assertFalse(Ledger.objects.filter(kind="charge").exists())

    @patch("studio.fal.requests.post", return_value=Response({"request_id": "safety-blocked-request"}, status=202))
    @patch("studio.fal.requests.get")
    @patch("studio.fal.storage.existing_generated_image", return_value=None)
    @patch("studio.fal.storage.put_generated_stream")
    def test_unsafe_provider_result_is_not_downloaded_or_stored_and_releases_user_credits(self, put, existing, get, post):
        job = self.job()
        worker.run_one()
        self.assertIs(post.call_args.kwargs["json"]["enable_safety_checker"], True)
        get.side_effect = [Response({"status": "COMPLETED"}), Response({"has_nsfw_concepts": [True],
                           "images": [{"url": "https://v3.fal.media/blocked.png"}]})]
        Job.objects.filter(pk=job.pk).update(next_attempt_at=timezone.now())
        worker.run_one()
        job.refresh_from_db(); self.wallet.refresh_from_db()
        self.assertEqual((job.state, job.output_id, job.charged_paise), ("failed", None, 0))
        self.assertEqual(job.error_code, "content_policy_violation")
        self.assertEqual((self.wallet.balance_paise, self.wallet.held_paise), (100_000, 0))
        self.assertEqual(get.call_count, 2)  # Only queue status/result; never GET the flagged image.
        self.assertFalse(Asset.objects.filter(account=self.account).exists())
        self.assertFalse(Ledger.objects.filter(account=self.account, kind="charge").exists())
        put.assert_not_called()
        post.assert_called_once()

    @patch("studio.fal.requests.post", return_value=Response({"request_id": "saved-output-request"}, status=202))
    @patch("studio.fal.requests.get")
    @patch("studio.fal.storage.existing_generated_image", return_value=None)
    @patch("studio.fal.storage.put_generated_stream")
    @patch("studio.worker.storage.provider_output")
    def test_storage_failure_keeps_job_incomplete_and_retry_saves_before_charging(self, output_asset, put, existing, get, post):
        job = self.job()
        worker.run_one()
        get.side_effect = [Response({"status": "COMPLETED"}), Response({"images": [{"url": "https://v3.fal.media/result.png"}]}), Response()]
        put.side_effect = IOError("S3 transfer interrupted")
        Job.objects.filter(pk=job.pk).update(next_attempt_at=timezone.now())
        worker.run_one()
        job.refresh_from_db(); self.wallet.refresh_from_db()
        self.assertEqual((job.state, job.output_id, self.wallet.balance_paise, self.wallet.held_paise), ("uncertain", None, 100_000, 100))
        self.assertFalse(Asset.objects.exists())
        self.assertFalse(Ledger.objects.filter(kind="charge").exists())
        output_asset.assert_not_called()

        asset = Asset.objects.create(account=self.account, key=storage.generated_key(job), mime="image/png", size=5, ready=True)
        output_asset.return_value = asset
        put.side_effect = None
        put.return_value = {"key": asset.key, "mime": asset.mime, "size": asset.size}
        get.side_effect = [Response({"status": "COMPLETED"}), Response({"images": [{"url": "https://v3.fal.media/result.png"}]}), Response()]
        Job.objects.filter(pk=job.pk).update(next_attempt_at=timezone.now())
        worker.run_one()
        job.refresh_from_db(); self.wallet.refresh_from_db()
        self.assertEqual((job.state, job.output_id, self.wallet.balance_paise, self.wallet.held_paise), ("succeeded", asset.pk, 99_900, 0))
        post.assert_called_once()
        self.assertEqual(Ledger.objects.filter(kind="charge").count(), 1)
        self.assertFalse(worker.run_one())

    @patch("studio.fal.requests.post", side_effect=requests.Timeout)
    def test_ambiguous_submission_is_never_retried_and_releases_wallet(self, post):
        job = self.job()
        worker.run_one(); worker.run_one()
        job.refresh_from_db(); self.wallet.refresh_from_db(); job.budget.refresh_from_db()
        self.assertEqual(job.state, "failed")
        self.assertEqual((self.wallet.balance_paise, self.wallet.held_paise), (100_000, 0))
        self.assertEqual(job.budget.spent_paise, 10)
        post.assert_called_once()

    @patch("studio.fal.storage.existing_generated_image", return_value=None)
    @patch("studio.fal.requests.post")
    def test_crash_without_provider_id_never_submits_again(self, post, existing):
        job = self.job()
        Job.objects.filter(pk=job.pk).update(state="submitting")
        worker.run_one(); job.refresh_from_db()
        self.assertEqual(job.state, "failed")
        post.assert_not_called()

    @patch("studio.fal.requests.post", return_value=Response({"request_id": "req-xyz"}, 202))
    @patch("studio.fal.requests.get", side_effect=requests.Timeout)
    @patch("studio.fal.storage.existing_generated_image", return_value=None)
    def test_poll_outage_keeps_provider_id_and_old_job_eventually_refunds(self, existing, get, post):
        job = self.job(); worker.run_one()
        Job.objects.filter(pk=job.pk).update(next_attempt_at=timezone.now())
        worker.run_one(); job.refresh_from_db()
        self.assertEqual((job.state, job.provider_id), ("uncertain", "req-xyz"))
        Job.objects.filter(pk=job.pk).update(created_at=timezone.now() - timedelta(hours=25), next_attempt_at=timezone.now())
        worker.run_one(); job.refresh_from_db(); self.wallet.refresh_from_db()
        self.assertEqual((job.state, self.wallet.held_paise), ("failed", 0))
        post.assert_called_once()

    @patch("studio.fal.requests.post", return_value=Response(status=422))
    def test_provider_rejection_releases_daily_budget(self, post):
        job = self.job(); worker.run_one(); job.refresh_from_db(); job.budget.refresh_from_db()
        self.assertEqual((job.state, job.actual_cost_paise, job.budget.reserved_paise, job.budget.spent_paise), ("failed", 0, 0, 0))

    @patch("studio.fal.requests.post")
    def test_submit_policy_rejection_is_final_and_releases_credits_without_retry(self, post):
        post.return_value = Response({"detail": [{"loc": ["body"], "type": "content_policy_violation", "categories": ["model_moderated"]}]}, status=422)
        job = self.job()
        worker.run_one()
        job.refresh_from_db(); self.wallet.refresh_from_db()
        self.assertEqual((job.state, job.error_code, job.charged_paise), ("failed", "content_policy_violation", 0))
        self.assertEqual((self.wallet.balance_paise, self.wallet.held_paise), (100_000, 0))
        self.assertIsNone(job.output_id)
        self.assertFalse(worker.run_one())
        self.assertFalse(Ledger.objects.filter(kind="charge").exists())
        post.assert_called_once()

    @patch("studio.fal.requests.post", return_value=Response({"request_id": "moderated-result"}, status=202))
    @patch("studio.fal.requests.get")
    @patch("studio.fal.storage.existing_generated_image", return_value=None)
    @patch("studio.fal.storage.put_generated_stream")
    def test_result_policy_rejection_survives_worker_and_never_downloads_blocked_media(self, put, existing, get, post):
        job = self.job()
        worker.run_one()
        get.side_effect = [Response({"status": "COMPLETED"}), Response([{"type": "content_policy_violation"}], status=422)]
        Job.objects.filter(pk=job.pk).update(next_attempt_at=timezone.now())
        worker.run_one()
        job.refresh_from_db(); self.wallet.refresh_from_db()
        self.assertEqual((job.state, job.error_code, job.charged_paise), ("failed", "content_policy_violation", 0))
        self.assertEqual((self.wallet.balance_paise, self.wallet.held_paise), (100_000, 0))
        self.assertIsNone(job.output_id)
        self.assertEqual(get.call_count, 2)
        self.assertFalse(worker.run_one())
        post.assert_called_once()
        put.assert_not_called()
