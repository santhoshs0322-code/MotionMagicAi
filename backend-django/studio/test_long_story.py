import json
import uuid
from decimal import Decimal
from unittest.mock import MagicMock, patch

from django.test import TestCase, override_settings
from django.utils import timezone

from . import long_story, services, worker, story_planner, fal_billing
from .errors import Problem
from .models import Account, Asset, Job, Ledger, Wallet


@override_settings(FAL_ONLY=True, CREDIT_ONLY_MODE=True, FAL_KEY="test-not-a-real-key", S3_BUCKET="bucket",
    DAILY_PROVIDER_BUDGET_PAISE=64_000_000, LONG_STORY_SECOND_COST=30, LONG_STORY_PLAN_COST=20,
    MARKUP_BPS=16667, STORE_FEE_BPS=1500, FAL_OVERHEAD_PAISE=0, OPENAI_API_KEY="test-only-openai-key")
class LongStoryTests(TestCase):
    def setUp(self):
        self.account = Account.objects.create(google_sub="long-story-one", email="one@example.test", name="One")
        self.other = Account.objects.create(google_sub="long-story-two", email="two@example.test", name="Two")
        Wallet.objects.create(account=self.account, balance_paise=100000, credit_buckets_version=1)
        self.rate = long_story.sync_rate(True)
        self.photo = Asset.objects.create(account=self.account, key=f"accounts/{self.account.pk}/image/uploads/a.png",
            mime="image/png", size=100, ready=True)

    def quote(self, **changes):
        data = dict(service=self.rate.pk, prompt="Two friends find a hidden city and speak English.", duration=60,
            generated_audio=True, input_kind="text", reference_image_count=1)
        return services.create_quote(self.account, {**data, **changes})

    def job(self):
        quote = self.quote()
        job, _ = services.submit_job(self.account, quote.pk, "story-request-123", reference_image_ids=[str(self.photo.pk)])
        job.lease_id = uuid.uuid4()
        job.save(update_fields=["lease_id"])
        return job

    def test_quote_includes_every_scene_and_planning(self):
        for duration in long_story.DURATIONS:
            quote = self.quote(duration=duration)
            self.assertEqual(quote.provider_cost_paise, 30 * duration + 20)
            self.assertEqual(quote.charge_paise, services.price(30 * duration + 20))
        for invalid in [15, 30, 61, 360, True]:
            with self.assertRaises(Problem): self.quote(duration=invalid)

    def test_requires_one_or_two_photos_and_audio(self):
        for count in [0, 3, "2", True, None]:
            with self.assertRaises(Problem): self.quote(reference_image_count=count)
        with self.assertRaises(Problem): self.quote(generated_audio=False)

    def test_foreign_reference_cannot_be_bound(self):
        photo = Asset.objects.create(account=self.other, key=f"accounts/{self.other.pk}/image/uploads/b.png", mime="image/png", size=100, ready=True)
        with self.assertRaises(Problem):
            services.submit_job(self.account, self.quote().pk, "foreign-story-123", reference_image_ids=[str(photo.pk)])
        self.assertEqual(Job.objects.count(), 0)

    def test_reservation_is_idempotent_one_parent_job(self):
        job = self.job()
        duplicate, created = services.submit_job(self.account, job.quote_id, "story-request-123", reference_image_ids=[str(self.photo.pk)])
        self.assertFalse(created)
        self.assertEqual(duplicate.pk, job.pk)
        self.assertEqual(Job.objects.count(), 1)
        self.assertEqual(Ledger.objects.filter(kind="reserve").count(), 1)

    @patch("studio.long_story.requests.post")
    def test_paid_intent_is_saved_before_post_and_never_retried(self, post):
        job = self.job()
        def check_intent(*args, **kwargs):
            saved = Job.objects.get(pk=job.pk).provider_context
            self.assertEqual(saved["steps"][-1]["state"], "posting")
            self.assertEqual(saved["spent_allowance"], 20)
            raise long_story.requests.Timeout()
        post.side_effect = check_intent
        self.assertEqual(long_story.advance(job)["state"], "failed")
        job.refresh_from_db()
        self.assertEqual(long_story.advance(job)["state"], "failed")
        self.assertEqual(post.call_count, 1)

    @patch("studio.long_story.requests.post")
    def test_plan_id_checkpoint(self, post):
        response = MagicMock(status_code=202)
        response.iter_content.return_value = [b'{"id":"resp_planner123"}']
        post.return_value.__enter__.return_value = response
        job = self.job()
        result = long_story.advance(job)
        self.assertEqual(result["id"], "resp_planner123")
        job.refresh_from_db()
        self.assertEqual(job.provider_context["steps"][0]["state"], "running")
        self.assertEqual(job.provider_context["steps"][0]["id"], "resp_planner123")

    def test_plan_validates_exact_count_and_complete_output(self):
        valid = {"output": json.dumps({"scenes": [{"prompt": "@Element1 speaks with natural synchronized lips."}] * 4})}
        self.assertEqual(len(long_story.parse_plan(valid, 4)), 4)
        with self.assertRaises(ValueError): long_story.parse_plan(valid, 8)
        with self.assertRaises(ValueError): long_story.parse_plan({**valid, "partial": True}, 4)
        with self.assertRaises(ValueError): long_story.parse_plan({"output": '{"scenes":[{"prompt":"bad"}]}'}, 1)

    @patch("studio.long_story.storage.client")
    def test_scene_has_native_audio_safety_and_owned_character_photos(self, client):
        client.return_value.generate_presigned_url.return_value = "https://bucket.s3.amazonaws.com/photo"
        job = self.job()
        body = long_story.scene_body(job, "@Element1 says hello.")
        self.assertIs(body["generate_audio"], True)
        self.assertIs(body["enable_safety_checker"], True)
        self.assertEqual(body["duration"], "15")
        self.assertEqual(len(body["elements"]), 1)
        self.assertNotIn("keep_audio", body)

    @patch("studio.story_assembly.assemble", return_value=None)
    @patch("studio.long_story.storage.existing_generated_video", return_value=None)
    def test_assembly_yields_without_completing_or_charging(self, existing, assemble):
        job = self.job()
        job.provider_context = dict(scenes_done=4, spent_allowance=1820,
            steps=[dict(kind="scene", state="done", output={}) for _ in range(4)])
        job.save()
        self.assertEqual(long_story.advance(job)["state"], "running")
        self.assertFalse(Ledger.objects.filter(kind="charge").exists())

    @patch("studio.long_story.requests.post")
    @patch("studio.long_story.requests.get")
    @patch("studio.long_story.storage.existing_generated_video")
    def test_saved_scene_after_crash_is_not_downloaded_or_generated_twice(self, existing, get, post):
        job = self.job()
        part = str(uuid.uuid4())
        output = dict(key=f"accounts/{self.account.pk}/video/generated/{part}/generated.mp4", mime="video/mp4", size=100)
        job.provider_context = dict(scenes_done=0, spent_allowance=470,
            scene_prompts=["A character speaks with natural synchronized lips."] * 4,
            steps=[dict(kind="plan", state="done", id="resp_existing", cost=20),
                   dict(kind="scene", state="running", id="saved-scene", cost=450, asset_job_id=part)])
        job.save()
        existing.return_value = output
        self.assertEqual(long_story.advance(job)["state"], "running")
        job.refresh_from_db()
        self.assertEqual(job.provider_context["scenes_done"], 1)
        self.assertEqual(job.provider_context["steps"][-1]["output"], output)
        post.assert_not_called()
        get.assert_not_called()

    def test_failure_releases_entire_hold_even_after_paid_scenes(self):
        job = self.job()
        services.finish_job(job.pk, "failed", 920)
        wallet = Wallet.objects.get(account=self.account)
        self.assertEqual(wallet.held_paise, 0)
        self.assertEqual(wallet.balance_paise, 100000)

    @override_settings(LONG_STORY_SECOND_COST=0)
    def test_missing_provider_price_disables_feature(self):
        self.assertFalse(long_story.sync_rate(True).enabled)

    @override_settings(OPENAI_API_KEY="")
    def test_missing_openai_key_disables_story_without_disabling_short_videos(self):
        self.assertFalse(services.enabled_rates().filter(pk=self.rate.pk).exists())
        self.assertFalse(long_story.sync_rate(True).enabled)

    def test_openai_body_has_bounded_strict_schema_and_no_tools(self):
        job = self.job()
        body = story_planner.body(job, long_story.plan_body(job))
        self.assertTrue(body["background"])
        self.assertEqual(body["max_output_tokens"], 12000)
        self.assertTrue(body["text"]["format"]["strict"])
        self.assertEqual(body["text"]["format"]["schema"]["properties"]["scenes"]["minItems"], 4)
        self.assertNotIn("tools", body)
        self.assertNotIn("test-only-openai-key", json.dumps(body))

    @patch("studio.story_planner.requests.get")
    def test_openai_refusal_and_incomplete_plan_are_terminal(self, get):
        response = MagicMock(status_code=200)
        get.return_value.__enter__.return_value = response
        response.iter_content.return_value = [json.dumps(dict(status="completed", output=[dict(type="message", content=[dict(type="refusal", refusal="blocked")])])).encode()]
        self.assertEqual(story_planner.poll("resp_test123")["error_code"], "content_policy_violation")
        response.iter_content.return_value = [b'{"status":"incomplete"}']
        self.assertEqual(story_planner.poll("resp_test123")["state"], "failed")
        self.assertEqual(story_planner.poll("https://attacker.invalid")["state"], "failed")
        self.assertEqual(get.call_count, 2)

    @patch("studio.story_planner.poll")
    @patch("studio.long_story.requests.post")
    def test_planner_restart_uses_saved_response_id_without_new_paid_post(self, post, poll):
        job = self.job()
        job.provider_context = dict(steps=[dict(kind="plan", state="running", id="resp_existing", cost=20)], spent_allowance=20, scenes_done=0)
        job.save()
        poll.return_value = dict(state="completed", usage={"input_tokens": 100, "output_tokens": 600},
            text=json.dumps({"scenes": [{"prompt": "@Element1 speaks English with synchronized lips."}] * 4}))
        self.assertEqual(long_story.advance(job)["state"], "running")
        post.assert_not_called()
        poll.assert_called_once_with("resp_existing")
        job.refresh_from_db()
        self.assertEqual(job.provider_context["planner_usage"]["input_tokens"], 100)
        self.assertEqual(len(job.provider_context["scene_prompts"]), 4)

    @patch("studio.long_story.storage.client")
    def test_cleanup_preserves_original_upload_and_final_video(self, client):
        job = self.job()
        with self.assertRaises(ValueError): long_story.cleanup(job)
        client.assert_not_called()
        services.finish_job(job.pk, "failed", 0)
        part = str(uuid.uuid4())
        key = f"accounts/{self.account.pk}/video/generated/{part}/generated.mp4"
        job.provider_context = dict(steps=[dict(kind="scene", asset_job_id=part, output=dict(key=key))])
        client.return_value.delete_objects.return_value = {}
        long_story.cleanup(job)
        deleted = client.return_value.delete_objects.call_args.kwargs["Delete"]["Objects"]
        self.assertIn({"Key": key}, deleted)
        self.assertNotIn(self.photo.key, str(deleted))
        self.assertNotIn(f"/generated/{job.pk}/generated.mp4", str(deleted))

    @patch("studio.fal_billing.fetch_events")
    @override_settings(FAL_BILLING_KEY="billing-test", USD_INR_RATE=Decimal("85"))
    def test_fal_video_subtotal_never_claims_to_cover_openai_bill(self, events):
        job = self.job()
        job.provider_id = "scene-one"
        job.provider_context = dict(steps=[dict(kind="plan", id="resp_one", cost=20), dict(kind="scene", id="scene-one", cost=450)])
        job.save()
        services.finish_job(job.pk, "failed", 470)
        events.return_value = [dict(request_id="scene-one", endpoint=long_story.VIDEO_ENDPOINT, timestamp=timezone.now().isoformat(), unit="seconds", cost="0.45")]
        self.assertEqual(fal_billing.sync_recent()["awaiting_billing"], 1)
        job.refresh_from_db()
        self.assertEqual(job.provider_context["fal_billed_usd"], "0.45")
        self.assertIsNone(job.cost_record_id)
        self.assertEqual(set(fal_billing.request_contracts(job)), {"scene-one"})

    @patch("studio.worker.provider.submit")
    def test_worker_persists_final_asset_before_success_and_charge(self, submit):
        job = self.job()
        job.lease_id = None
        job.save()
        output = Asset.objects.create(account=self.account, key=f"accounts/{self.account.pk}/video/generated/{job.pk}/generated.mp4", mime="video/mp4", size=1000, ready=True)
        submit.return_value = dict(state="succeeded", cost_paise=1820, currency="INR", output={})
        with patch("studio.worker.storage.provider_output", return_value=output), patch("studio.worker.thumbnails.generate"), patch("studio.long_story.cleanup"):
            worker.run_one()
        job.refresh_from_db()
        self.assertEqual(job.state, "succeeded")
        self.assertEqual(job.output_id, output.pk)
        self.assertEqual(job.charged_paise, job.quote.charge_paise)
