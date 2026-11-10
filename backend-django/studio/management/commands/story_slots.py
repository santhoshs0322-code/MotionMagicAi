import uuid
from django.core.management.base import BaseCommand, CommandError
from django.db import transaction
from studio.models import StoryWorkSlot
from studio.story_slots import EMPTY, TERMINAL


class Command(BaseCommand):
    help = "Inspect global story permits; release unknown requests only after checking fal is idle."

    def add_arguments(self, parser):
        parser.add_argument("--release", "--release-unknown", dest="release_unknown")
        parser.add_argument("--token")
        parser.add_argument("--confirmed-provider-idle", action="store_true")

    def handle(self, *args, **options):
        if options['release_unknown']:
            try:
                token = uuid.UUID(options['token'] or '')
            except ValueError:
                raise CommandError('Pass the exact permit token shown by story_slots.') from None
            if not options['confirmed_provider_idle']:
                raise CommandError('First check fal has no running request for this attempt, then pass --confirmed-provider-idle.')
            with transaction.atomic():
                StoryWorkSlot.objects.select_for_update().get(pk='scene-1')
                slot = StoryWorkSlot.objects.select_for_update().filter(pk=options['release_unknown'], name__startswith='scene-', token=token).first()
                if slot is None or slot.job is None or slot.job.state not in TERMINAL:
                    raise CommandError('Only a matching permit on a terminal story can be released. Check the provider request is idle first.')
                StoryWorkSlot.objects.filter(pk=slot.pk, token=token).update(**EMPTY)
                self.stdout.write(f'Released reconciled permit {slot.pk}. Customer balances were not changed.')
        for slot in StoryWorkSlot.objects.order_by('name'):
            self.stdout.write(f'{slot.pk}: ' + (f'job={slot.job_id} token={slot.token} request={slot.request_id or "UNKNOWN — reconcile before release"}' if slot.job_id else 'free'))
