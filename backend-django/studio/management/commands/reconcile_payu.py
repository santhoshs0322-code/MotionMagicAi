from django.core.management.base import BaseCommand
from studio.web_payments import reconcile_recent


class Command(BaseCommand):
    help = "Reconcile up to five due PayU payments/refunds using server verification."

    def handle(self, *args, **kwargs):
        reconcile_recent(limit=5)
        self.stdout.write("PayU reconciliation pass complete")
