from django.core.cache import cache
from django.core.management.base import BaseCommand
from studio.bundled_templates import catalogue
from studio.models import Rate, ShowcaseTemplate


class Command(BaseCommand):
    help = "Create the ten initial image templates once; preserve all subsequent owner edits."

    def handle(self, *args, **options):
        created = 0
        for entry in catalogue():
            rate = Rate.objects.filter(pk=entry["rate"]).first()
            if rate is None:
                continue  # sync_provider_rates creates the model catalogue first.
            _, added = ShowcaseTemplate.objects.get_or_create(pk=entry["id"], defaults={
                "title": entry["title"], "category": entry["category"], "prompt": entry["prompt"],
                "mode": entry["mode"], "rate": rate, "aspect_ratio": entry["aspect_ratio"],
                "duration": 0, "position": entry["position"], "requires_reference": True,
                "poster_key": "builtin:" + entry["file"], "published": True, "featured": entry["position"] == 0,
            })
            created += int(added)
        if created:
            cache.delete("motion:showcase:feed:v2")
        self.stdout.write(f"Image templates: {created} added; existing owner changes preserved.")
