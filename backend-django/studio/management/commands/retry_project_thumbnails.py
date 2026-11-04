from django.core.management.base import BaseCommand
from studio.models import Asset


class Command(BaseCommand):
    help = "Queue failed result previews again, without regenerating or charging for media."

    def add_arguments(self, parser):
        parser.add_argument("--limit", type=int, default=100)

    def handle(self, *args, **options):
        identifiers = list(Asset.objects.filter(thumbnail_state="failed", ready=True, deleted=False)
                           .order_by("created_at").values_list("pk", flat=True)[:max(1, min(options["limit"], 1000))])
        count = Asset.objects.filter(pk__in=identifiers, thumbnail_state="failed").update(
            thumbnail_state="pending", thumbnail_lease_until=None, thumbnail_attempts=0)
        self.stdout.write(f"Queued {count} result previews. The worker will process them when idle.")
