from datetime import timedelta
from django.core.management.base import BaseCommand
from django.utils import timezone
from studio.models import Revocation, Quote

class Command(BaseCommand):
    help = "Bounded cleanup; ledger and purchase history are never deleted."
    def handle(self, *args, **options):
        revocations = list(Revocation.objects.filter(expires_at__lt=timezone.now()).values_list("pk", flat=True)[:1000])
        Revocation.objects.filter(pk__in=revocations).delete()
        quotes = list(Quote.objects.filter(expires_at__lt=timezone.now()-timedelta(days=1), job__isnull=True).values_list("pk", flat=True)[:1000])
        Quote.objects.filter(pk__in=quotes).delete()
        self.stdout.write(f"Removed {len(revocations)} expired revocations and {len(quotes)} unused quotes")
