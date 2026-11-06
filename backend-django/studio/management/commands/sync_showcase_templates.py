from django.core.cache import cache
from django.core.management.base import BaseCommand
from studio.bundled_templates import catalogue, banner_catalogue
from studio.models import Rate, ShowcaseTemplate, ShowcaseBanner


class Command(BaseCommand):
    help = "Import the bundled image templates once; preserve all subsequent owner edits."

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
        banners = 0
        for entry in banner_catalogue():
            _, added = ShowcaseBanner.objects.get_or_create(pk=entry["id"], defaults={
                "title": entry["title"], "image_key": "builtin:" + entry["file"], "destination": entry["destination"],
                "position": entry["position"], "display_seconds": entry["display_seconds"], "published": True,
            })
            banners += int(added)
        if created or banners:
            cache.delete_many(["motion:showcase:feed:v2", "motion:showcase:feed:v3"])
        self.stdout.write(f"Image templates: {created} added; existing owner changes preserved.")
        self.stdout.write(f"Home banners: {banners} added; existing owner changes preserved.")
