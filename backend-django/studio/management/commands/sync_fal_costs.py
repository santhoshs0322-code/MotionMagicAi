from django.core.management.base import BaseCommand, CommandError
from studio.fal_billing import sync_recent


class Command(BaseCommand):
    help = "Read fal billing events and reconcile per-job costs. Does not generate media or change wallets."
    def add_arguments(self, parser):
        parser.add_argument("--days", type=int, default=7)
        parser.add_argument("--limit", type=int, default=100)
    def handle(self, *args, **options):
        try: result = sync_recent(options["days"], options["limit"])
        except Exception as exc:
            # Do not expose upstream response bodies, URLs or credentials.
            raise CommandError("Cost sync failed. Check billing-key permissions, FX configuration, date range and provider availability. Existing records were preserved.") from None
        self.stdout.write(str(result))
