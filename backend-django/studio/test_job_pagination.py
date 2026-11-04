import uuid
from datetime import timedelta
from urllib.parse import urlsplit, parse_qs

from django.db import connection
from django.test import TestCase
from django.test.utils import CaptureQueriesContext
from django.utils import timezone
from rest_framework.test import APIClient

from .authentication import issue_token
from .models import Account, Budget, Job, Quote, Rate


class JobPaginationTests(TestCase):
    def setUp(self):
        self.account = Account.objects.create(google_sub="history-owner", email="history@example.test", name="Owner")
        self.other = Account.objects.create(google_sub="history-other", email="other@example.test", name="Other")
        self.rate = Rate.objects.create(slug="history", title="Image", kind="image", enabled=True,
                                        provider_model="gateway/image", unit_cost_paise=1, max_units=1)
        self.budget = Budget.objects.create(day=timezone.now().date(), shard=0)
        self.client = APIClient()
        self.client.credentials(HTTP_AUTHORIZATION="Bearer " + issue_token(self.account))
        for number in range(1, 66):
            self.make_job(self.account, number)
        self.foreign = self.make_job(self.other, 100)
        # Equal timestamps exercise the UUID tie-breaker across every page.
        Job.objects.update(created_at=timezone.now() - timedelta(minutes=1))

    def make_job(self, account, number):
        quote = Quote.objects.create(account=account, rate=self.rate, payload={"prompt": f"Scene {number}", "kind": "image"},
                                     payload_hash=str(number), provider_cost_paise=1, overhead_paise=0,
                                     markup_bps=0, fee_bps=0, charge_paise=2, expires_at=timezone.now() + timedelta(minutes=5))
        return Job.objects.create(id=uuid.UUID(int=number), account=account, quote=quote, budget=self.budget,
                                  state="succeeded", idempotency_key=f"history-{number}", next_attempt_at=timezone.now())

    def test_pages_use_stable_boundaries_even_with_tracked_jobs_and_new_insertions(self):
        oldest = str(uuid.UUID(int=1))
        with CaptureQueriesContext(connection) as queries:
            first = self.client.get(f"/api/v1/jobs?tracked={oldest},{self.foreign.pk}").json()
        self.assertEqual(len(first["results"]), 31)  # 30 page records + one older recovery record.
        self.assertIn(oldest, {row["id"] for row in first["results"]})
        self.assertNotIn(str(self.foreign.pk), {row["id"] for row in first["results"]})
        self.assertTrue(first["has_more"])
        self.assertNotIn("tracked", parse_qs(urlsplit(first["next"]).query))
        self.assertFalse(any("OFFSET" in query["sql"].upper() or "COUNT(" in query["sql"].upper() for query in queries))
        self.assertTrue(any("LIMIT 31" in query["sql"].upper() for query in queries))
        inserted = self.make_job(self.account, 200)
        seen = {row["id"] for row in first["results"]}
        cursor = first["next_cursor"]
        page_sizes = []
        while cursor:
            response = self.client.get("/api/v1/jobs", {"cursor": cursor})
            self.assertEqual(response.status_code, 200)
            page = response.json()
            page_sizes.append(len(page["results"]))
            seen.update(row["id"] for row in page["results"])
            cursor = page["next_cursor"]
        self.assertEqual(page_sizes, [30, 5])
        self.assertEqual(seen, {str(uuid.UUID(int=number)) for number in range(1, 66)})
        self.assertIsNone(page["next"])
        self.assertFalse(page["has_more"])
        refreshed = self.client.get("/api/v1/jobs").json()
        self.assertEqual(refreshed["results"][0]["id"], str(inserted.pk))

    def test_cursor_cannot_be_changed_reused_by_another_account_or_for_active_scope(self):
        cursor = self.client.get("/api/v1/jobs").json()["next_cursor"]
        self.assertEqual(self.client.get("/api/v1/jobs", {"cursor": cursor + "x"}).status_code, 400)
        self.assertEqual(self.client.get("/api/v1/jobs", {"cursor": "x" * 1025}).status_code, 400)
        self.assertEqual(self.client.get("/api/v1/jobs", {"cursor": cursor, "scope": "active"}).status_code, 400)
        self.client.credentials(HTTP_AUTHORIZATION="Bearer " + issue_token(self.other))
        self.assertEqual(self.client.get("/api/v1/jobs", {"cursor": cursor}).status_code, 400)
        other = self.client.get("/api/v1/jobs").json()
        self.assertEqual([row["id"] for row in other["results"]], [str(self.foreign.pk)])
        self.assertFalse(other["has_more"])
