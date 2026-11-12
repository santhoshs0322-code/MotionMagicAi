import signal
import time
import uuid
from django.core.cache import cache
from django.core.management.base import BaseCommand
from django.db import close_old_connections
from studio.web_payments import reconcile_recent


class Command(BaseCommand):
    help = "Bounded PayU reconciliation worker, separate from AI generation workers."

    def add_arguments(self, parser):
        parser.add_argument("--once", action="store_true")

    def handle(self, *args, **options):
        stopping = False
        def stop(*_):
            nonlocal stopping
            stopping = True
        signal.signal(signal.SIGTERM, stop)
        signal.signal(signal.SIGINT, stop)
        while not stopping:
            close_old_connections()
            token = uuid.uuid4().hex
            try:
                if cache.add("motion:web-payments-worker", token, 300):
                    try:
                        reconcile_recent(limit=5)
                    finally:
                        if cache.get("motion:web-payments-worker")==token:
                            cache.delete("motion:web-payments-worker")
            except Exception:
                self.stderr.write("Web payment reconciliation delayed; orders retained for retry.")
            if options["once"]:
                return
            for _ in range(30):
                if stopping:
                    return
                time.sleep(1)
