import signal
import time
from django.conf import settings
from django.core.management.base import BaseCommand, CommandError
from django.db import close_old_connections
from studio.worker import run_one

class Command(BaseCommand):
    help = "Run one bounded generation worker; scale separate ECS tasks, never API threads."
    def add_arguments(self, parser):
        parser.add_argument("--once", action="store_true")
    def handle(self, *args, **options):
        if not settings.GATEWAY_URL:
            raise CommandError("Configure and validate the generation gateway first")
        stopping = False
        def stop(*_):
            nonlocal stopping
            stopping = True
        signal.signal(signal.SIGTERM, stop)
        signal.signal(signal.SIGINT, stop)
        while not stopping:
            close_old_connections()
            try:
                worked = run_one()
            except Exception:
                self.stderr.write("Worker iteration failed; pending job retained for retry.")
                worked = False
            if options["once"]:
                break
            if not worked:
                time.sleep(2)
