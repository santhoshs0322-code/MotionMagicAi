import io
import json
from datetime import timedelta
from unittest.mock import patch, Mock

import requests
from django.core.management import call_command
from django.test import TestCase, SimpleTestCase, override_settings
from django.utils import timezone

from . import fal, fal_catalog, services, worker, storage
from .models import Account, Asset, Wallet, Job, Rate, Ledger

RUNTIME = dict(FAL_KEY="test-server-key", GEMINI_API_KEY="", GATEWAY_URL="", GATEWAY_TOKEN="", S3_BUCKET="test-bucket",
               DAILY_PROVIDER_BUDGET_PAISE=6_400_000, FAL_RATE_COSTS={slug: 10 for slug, *_ in fal_catalog.variants()})


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
            for source in (None, "https://test-bucket.s3.amazonaws.com/photo"):
                for duration in config["durations"]:
                    payload = {"model": "fal:" + config["app_model_id"], "config": config, "aspect_ratio": "9:16", "prompt": "A beach", "units": duration, "generated_audio": config["generated_audio"]}
                    endpoint, body = fal_catalog.request(payload, source)
                    self.assertEqual(body.get("image_url"), source)
                    self.assertEqual(body.get("aspect_ratio"), None if source else "9:16")
                    if "kling" in endpoint:
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

    def test_settings_are_frozen_and_silent_price_cannot_buy_native_audio(self):
        job = self.job("kling-3-omni-720p-silent", duration=5, generated_audio=False)
        self.assertEqual(job.quote.charge_paise, 250)
        Rate.objects.filter(pk=job.quote.rate_id).update(config={"resolution": "1080p"})
        endpoint, body = fal_catalog.request(job.quote.payload)
        self.assertIn("/standard/", endpoint)
        self.assertFalse(body["generate_audio"])
        with self.assertRaisesMessage(Exception, "audio setting"):
            services.create_quote(self.account, {"service": "pixverse-v6-720p-silent", "prompt": "A beach", "generated_audio": True})

    def test_other_accounts_and_oversized_photos_are_rejected_before_charging(self):
        other = Account.objects.create(google_sub="other", email="other@example.test", name="Other")
        asset = Asset.objects.create(account=other, key="other/image", mime="image/png", size=100, ready=True)
        with self.assertRaisesMessage(Exception, "Source media is unavailable"):
            services.create_quote(self.account, {"service": "qwen-image-720p", "prompt": "Photo", "asset_id": str(asset.pk)})
        asset.account = self.account; asset.size = 20 * 1024**2; asset.save()
        with self.assertRaisesMessage(Exception, "too large"):
            services.create_quote(self.account, {"service": "qwen-image-720p", "prompt": "Photo", "asset_id": str(asset.pk)})
        self.assertFalse(Ledger.objects.exists())

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
        self.wallet.refresh_from_db(); self.assertEqual(self.wallet.held_paise, 50)
        asset = Asset.objects.create(account=self.account, key=f"accounts/{self.account.pk}/outputs/{job.pk}/generated.png", mime="image/png", size=5, ready=True)
        output_asset.return_value = asset
        put.return_value = {"key": asset.key, "mime": asset.mime, "size": asset.size}
        get.side_effect = [Response({"status": "COMPLETED"}), Response({"images": [{"url": "https://v3.fal.media/result.png"}]}), Response()]
        Job.objects.filter(pk=job.pk).update(next_attempt_at=timezone.now())
        worker.run_one(); job.refresh_from_db(); self.wallet.refresh_from_db()
        self.assertEqual((job.state, job.output_id, job.charged_paise), ("succeeded", asset.pk, 50))
        self.assertEqual((self.wallet.balance_paise, self.wallet.held_paise), (99_950, 0))
        post.assert_called_once()
        self.assertFalse(worker.run_one())
        self.assertNotIn("headers", get.call_args.kwargs)  # No credentials on media GET.
        self.assertEqual(Ledger.objects.filter(kind="charge").count(), 1)

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
