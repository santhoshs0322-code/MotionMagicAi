import importlib
import io
import json
import uuid
from datetime import timedelta
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

from django.apps import apps
from django.core.management import call_command, CommandError
from django.db import connection
from django.test import TestCase, override_settings
from django.utils import timezone

from . import long_story, services, story_slots
from .models import Account, Job, Ledger, StoryWorkSlot, Wallet


def response(data, status=200):
    result = MagicMock(status_code=status)
    result.iter_content.side_effect = lambda *args: iter([json.dumps(data).encode()])
    result.__enter__.return_value = result
    return result


@override_settings(FAL_ONLY=True, CREDIT_ONLY_MODE=True, FAL_KEY="test-only-fal", OPENAI_API_KEY="test-only-openai",
    S3_BUCKET="bucket", DAILY_PROVIDER_BUDGET_PAISE=64_000_000, LONG_STORY_SECOND_COST=30,
    LONG_STORY_PLAN_COST=20, MARKUP_BPS=16667, STORE_FEE_BPS=1500, FAL_OVERHEAD_PAISE=0)
class ParallelStoryTests(TestCase):
    def setUp(self):
        self.rate = long_story.sync_rate(True)

    def job(self):
        key = uuid.uuid4().hex
        account = Account.objects.create(google_sub=key, email=f"{key}@example.test", name="Story test")
        Wallet.objects.create(account=account, balance_paise=100000, credit_buckets_version=1)
        quote = services.create_quote(account, dict(service=self.rate.pk, prompt="Four friends discover a hidden city.",
            input_kind="text", reference_image_count=0, duration=60, generated_audio=True))
        job, _ = services.submit_job(account, quote.pk, key)
        job.state, job.lease_id = "running", uuid.uuid4()
        job.lease_until = timezone.now() + timedelta(seconds=1200)
        job.provider_context = dict(steps=[dict(kind="plan", state="done", id="resp_" + key, cost=20)],
            scene_prompts=[f"Scene {index}: The friends speak with synchronized lips." for index in range(4)],
            spent_allowance=20, scenes_done=0)
        job.save()
        return job

    def scenes(self, job):
        return [step for step in job.provider_context["steps"] if step["kind"] == "scene"]

    def due(self, job, index):
        self.scenes(job)[index]["next_poll_at"] = 0
        long_story.save(job)

    def occupied(self):
        return StoryWorkSlot.objects.filter(name__startswith="scene-", job__isnull=False).count()

    @patch("studio.long_story.requests.post")
    def test_two_requests_start_before_first_finishes_and_third_waits(self, post):
        post.side_effect = [response({"request_id": "scene-0"}), response({"request_id": "scene-1"})]
        job = self.job()
        original_hold = Wallet.objects.get(account=job.account).held_paise
        long_story.advance(job)
        job.refresh_from_db()
        long_story.advance(job)
        job.refresh_from_db()
        self.assertEqual([step["state"] for step in self.scenes(job)], ["running", "running"])
        self.assertEqual(self.occupied(), 2)
        self.assertEqual(post.call_count, 2)
        self.assertEqual(long_story.advance(job)["state"], "running")
        self.assertEqual(post.call_count, 2)
        self.assertEqual(Wallet.objects.get(account=job.account).held_paise, original_hold)
        self.assertEqual(Ledger.objects.filter(kind="reserve").count(), 1)

    @patch("studio.long_story.requests.post")
    def test_limit_is_global_across_accounts_and_workers(self, post):
        post.side_effect = [response({"request_id": "user-one-scene"}), response({"request_id": "user-two-scene"})]
        jobs = [self.job() for _ in range(3)]
        long_story.advance(jobs[0])
        long_story.advance(jobs[1])
        self.assertEqual(long_story.advance(jobs[2])["state"], "running")
        self.assertEqual(post.call_count, 2)
        self.assertEqual(self.occupied(), 2)
        jobs[2].refresh_from_db()
        self.assertEqual(len(self.scenes(jobs[2])), 0)
        self.assertEqual(jobs[2].provider_context["spent_allowance"], 20)

    @patch("studio.long_story.fal.download")
    @patch("studio.long_story.storage.existing_generated_video", return_value=None)
    @patch("studio.long_story.requests.get")
    @patch("studio.long_story.requests.post")
    def test_out_of_order_completion_starts_next_unused_scene(self, post, get, existing, download):
        post.side_effect = [response({"request_id": f"request-{i}"}) for i in range(3)]
        job = self.job()
        long_story.advance(job); long_story.advance(job)
        first, second = self.scenes(job)
        first["next_poll_at"] = timezone.now().timestamp() + 120
        self.due(job, 1)
        output = dict(key=f"accounts/{job.account_id}/video/generated/{second['asset_job_id']}/generated.mp4", mime="video/mp4", size=100)
        get.side_effect = [response({"status": "COMPLETED"}), response({"video": {"url": "https://fal.media/result.mp4"}})]
        download.return_value = dict(state="succeeded", output=output)
        long_story.advance(job)
        self.assertEqual(job.provider_context["scenes_done"], 1)
        self.assertEqual([step["state"] for step in self.scenes(job)], ["running", "done"])
        long_story.advance(job)
        self.assertEqual(post.call_args.kwargs["json"]["prompt"], job.provider_context["scene_prompts"][2])
        self.assertEqual([step["scene_index"] for step in self.scenes(job)], [0, 1, 2])
        self.assertEqual(self.occupied(), 2)

    @patch("studio.story_assembly.assemble")
    @patch("studio.long_story.storage.existing_generated_video", return_value=None)
    def test_assembly_always_receives_story_order_and_releases_permit(self, existing, assemble):
        job = self.job()
        job.provider_context["steps"] += [dict(kind="scene", state="done", scene_index=i, output={"scene": i}) for i in (3, 1, 0, 2)]
        job.provider_context.update(scenes_done=4, spent_allowance=1820)
        job.save()
        assemble.return_value = dict(key="final", mime="video/mp4", size=100)
        self.assertEqual(long_story.advance(job)["state"], "succeeded")
        self.assertEqual(assemble.call_args.args[1], [{"scene": i} for i in range(4)])
        self.assertIsNone(StoryWorkSlot.objects.get(pk="assembly").job_id)
        self.assertFalse(Ledger.objects.filter(kind="charge").exists())

    def test_single_assembly_permit_and_old_worker_cannot_release_new_owner(self):
        first, second = self.job(), self.job()
        self.assertTrue(story_slots.acquire_assembly(first))
        self.assertFalse(story_slots.acquire_assembly(second))
        story_slots.release_assembly(second)
        self.assertEqual(StoryWorkSlot.objects.get(pk="assembly").job_id, first.pk)
        StoryWorkSlot.objects.filter(pk="assembly").update(expires_at=timezone.now() - timedelta(seconds=1))
        Job.objects.filter(pk=first.pk).update(lease_until=timezone.now() - timedelta(seconds=400))
        self.assertTrue(story_slots.acquire_assembly(second))
        story_slots.release_assembly(first)
        self.assertEqual(StoryWorkSlot.objects.get(pk="assembly").job_id, second.pk)
        with self.assertRaises(RuntimeError): story_slots.require_assembly_lease(first, 240)
        story_slots.require_assembly_lease(second, 240)

    @patch("studio.long_story.requests.post", side_effect=long_story.requests.Timeout)
    def test_ambiguous_post_is_not_retried_or_automatically_released(self, post):
        job = self.job()
        result = long_story.advance(job)
        self.assertEqual(result["state"], "failed")
        services.finish_job(job.pk, "failed", result["cost_paise"])
        job.refresh_from_db()
        long_story.advance(job)
        self.assertEqual(post.call_count, 1)
        self.assertEqual(self.occupied(), 1)
        self.assertFalse(story_slots.reap_one())
        wallet = Wallet.objects.get(account=job.account)
        self.assertEqual((wallet.balance_paise, wallet.held_paise), (100000, 0))
        slot = StoryWorkSlot.objects.get(job_id=job.pk)
        with self.assertRaises(CommandError):
            call_command("story_slots", release_unknown=slot.pk, token=str(slot.token), stdout=io.StringIO())
        call_command("story_slots", release_unknown=slot.pk, token=str(slot.token), confirmed_provider_idle=True, stdout=io.StringIO())
        self.assertEqual(self.occupied(), 0)

    @patch("studio.long_story.storage.existing_generated_video", return_value=None)
    @patch("studio.long_story.requests.get")
    @patch("studio.long_story.requests.post")
    def test_failed_story_refunds_but_other_paid_request_holds_permit_until_finished(self, post, get, existing):
        post.side_effect = [response({"request_id": "first-scene"}), response({"request_id": "second-scene"})]
        job = self.job()
        long_story.advance(job); long_story.advance(job)
        self.due(job, 0)
        get.return_value = response({"detail": [{"type": "content_policy_violation"}]}, 422)
        result = long_story.advance(job)
        self.assertEqual(result["error_code"], "content_policy_violation")
        services.finish_job(job.pk, "failed", result["cost_paise"])
        self.assertEqual(self.occupied(), 1)
        self.assertEqual(Wallet.objects.get(account=job.account).held_paise, 0)
        get.return_value = response({"status": "IN_PROGRESS"})
        self.assertTrue(story_slots.reap_one())
        self.assertEqual(self.occupied(), 1)
        StoryWorkSlot.objects.filter(job_id=job.pk).update(next_check_at=timezone.now())
        get.return_value = response({"status": "COMPLETED"})
        story_slots.reap_one()
        self.assertEqual(self.occupied(), 0)

    def test_lost_lease_rolls_back_permit_and_paid_intent_together(self):
        job = self.job()
        Job.objects.filter(pk=job.pk).update(lease_id=uuid.uuid4())
        step = dict(kind="scene", state="posting", cost=450, asset_job_id=str(uuid.uuid4()))
        with self.assertRaises(ValueError): story_slots.reserve_scene(job, step)
        self.assertEqual(self.occupied(), 0)
        job.refresh_from_db()
        self.assertEqual(job.provider_context["spent_allowance"], 20)
        self.assertEqual(self.scenes(job), [])

    def test_rollout_adopts_legacy_requests_and_blocks_new_work_during_overflow(self):
        jobs = [self.job() for _ in range(3)]
        for index, job in enumerate(jobs):
            job.provider_context["steps"].append(dict(kind="scene", state="running", cost=450,
                asset_job_id=str(uuid.uuid4()), id=f"legacy-{index}"))
            job.save()
        migration = importlib.import_module("studio.migrations.0022_story_work_slots")
        migration.initialize_slots(apps, SimpleNamespace(connection=connection))
        migration.initialize_slots(apps, SimpleNamespace(connection=connection))
        self.assertEqual(self.occupied(), 3)
        self.assertFalse(story_slots.available())
        self.assertEqual(StoryWorkSlot.objects.filter(name__startswith="scene-legacy-", job__isnull=False).count(), 1)
        for job in jobs[:2]: story_slots.release_scene(job, self.scenes(job)[0])
        self.assertTrue(story_slots.available())
