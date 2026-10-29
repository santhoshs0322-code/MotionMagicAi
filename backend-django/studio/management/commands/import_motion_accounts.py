import json
from django.core.management.base import BaseCommand, CommandError
from django.db import transaction
from studio.models import Account, Wallet

class Command(BaseCommand):
    help = "Import a MotionMagic-only JSONL export from MongoDB, preserving account IDs. No sessions are imported."
    def add_arguments(self, parser):
        parser.add_argument("file")
        parser.add_argument("--apply", action="store_true")
    def handle(self, *args, **options):
        count = 0
        with transaction.atomic():
            with open(options["file"], encoding="utf-8") as source:
                for line in source:
                    row = json.loads(line)
                    identifier = row.get("_id")
                    if isinstance(identifier, dict): identifier = identifier.get("$oid")
                    sub, email = row.get("googleSub"), row.get("email")
                    if not isinstance(identifier, str) or len(identifier) != 24 or not all(c in "0123456789abcdef" for c in identifier) or not isinstance(sub, str) or not 1 <= len(sub) <= 255 or not isinstance(email, str) or not 1 <= len(email) <= 320:
                        raise CommandError("Invalid MotionMagic account export; nothing committed")
                    account, created = Account.objects.get_or_create(google_sub=sub, defaults={"id": identifier, "email": email, "name": str(row.get("name") or email)[:200]})
                    if account.pk != identifier:
                        raise CommandError("Account ID conflict; nothing committed")
                    Wallet.objects.get_or_create(account=account)
                    count += int(created)
            if not options["apply"]:
                transaction.set_rollback(True)
        self.stdout.write(f"{'Imported' if options['apply'] else 'Dry run: would import'} {count} accounts; sign-in is required after cutover")
