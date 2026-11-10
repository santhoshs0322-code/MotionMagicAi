from datetime import timedelta
from django.core.management.base import BaseCommand
from django.utils import timezone
from studio.long_story import cleanup, MODEL
from studio.models import Job


class Command(BaseCommand):
    help = "Retry removal of only terminal Long Story intermediate files (bounded batch)."

    def handle(self, *args, **options):
        jobs = Job.objects.filter(quote__payload__model=MODEL, settled_at__lte=timezone.now() - timedelta(hours=1),
            state__in=("succeeded", "failed", "cancelled")).exclude(provider_context__has_key="parts_deleted").select_related("quote").order_by("settled_at")[:100]
        count = 0
        for job in jobs:
            cleanup(job)
            count += 1
        self.stdout.write(f"Cleaned intermediate files for {count} terminal stories")
