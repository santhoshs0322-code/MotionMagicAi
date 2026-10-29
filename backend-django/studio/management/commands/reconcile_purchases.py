from datetime import timedelta
from django.conf import settings
from django.core.management.base import BaseCommand, CommandError
from django.utils import timezone
from django.db.models import Q
from studio.models import Purchase
from studio.billing import reconcile_purchase

class Command(BaseCommand):
    help = "Verify store status and retry consumption; run repeatedly as a scheduled single task."
    def add_arguments(self, parser):
        parser.add_argument("--limit", type=int, default=100)
    def handle(self, *args, **options):
        if not settings.PLAY_ENABLED:
            raise CommandError("Google Play is not configured")
        rows = Purchase.objects.exclude(state="refunded").filter(Q(checked_at__isnull=True) | Q(checked_at__lt=timezone.now() - timedelta(hours=1))).order_by("checked_at", "created_at")[:max(1, min(options["limit"], 1000))]
        done = failed = 0
        for purchase in rows:
            try:
                reconcile_purchase(purchase)
                done += 1
            except Exception:
                failed += 1
        self.stdout.write(f"Reconciled={done}; unresolved={failed}. No tokens are logged.")
