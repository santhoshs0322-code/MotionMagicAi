import signal
import time
from django.core.management.base import BaseCommand, CommandError
from django.db import close_old_connections
from django.core.cache import cache
from studio import fal_billing
from studio.worker import run_one
from studio.provider import configured

class Command(BaseCommand):
    help = "Run one bounded generation worker; scale separate ECS tasks, never API threads."
    def add_arguments(self, parser):
        parser.add_argument("--once", action="store_true")
    def handle(self, *args, **options):
        if not configured():
            raise CommandError("Configure fal.ai, Gemini or the generation gateway first")
        stopping = False
        def stop(*_):
            nonlocal stopping
            stopping = True
        signal.signal(signal.SIGTERM, stop)
        signal.signal(signal.SIGINT, stop)
        last_cost_sync = 0
        while not stopping:
            close_old_connections()
            try:
                worked = run_one()
            except Exception:
                self.stderr.write("Worker iteration failed; pending job retained for retry.")
                worked = False
            if options["once"]:
                break
            if fal_billing.configured() and time.monotonic() - last_cost_sync >= 300:
                last_cost_sync = time.monotonic()
                try:
                    if cache.add("motion:fal-cost-sync", True, 300):
                        fal_billing.sync_recent(days=7, limit=25)
                except Exception:
                    self.stderr.write("Provider cost sync delayed; records retained. Check billing permissions and FX settings.")
            if not worked:
                time.sleep(2)
