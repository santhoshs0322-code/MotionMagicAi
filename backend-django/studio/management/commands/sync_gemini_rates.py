from django.conf import settings
from django.core.management.base import BaseCommand

from studio.gemini import ASPECT_RATIOS
from studio.models import Rate


CATALOG = {
    "gemini-nano-banana-2.1": ("Nano Banana 2.1", "nano-banana-21"),
    "gemini-3.1-flash-image": ("Nano Banana 2", "nano-banana-2"),
    "gemini-3.1-flash-lite-image": ("Nano Banana 2 Lite", "nano-banana-2-lite"),
    "gemini-3-pro-image": ("Nano Banana Pro", "nano-banana-pro"),
}


class Command(BaseCommand):
    help = "Create/update only configured Gemini image rates; zero-cost services stay disabled."

    def handle(self, *args, **options):
        configured = bool(settings.GEMINI_API_KEY and settings.S3_BUCKET and settings.DAILY_PROVIDER_BUDGET_PAISE > 0)
        for (model, size), cost in settings.GEMINI_RATE_COSTS.items():
            title, prefix = CATALOG[model]
            slug = f"{prefix}-{size.lower()}"
            values = {
                "title": f"{title} {size}",
                "kind": "image",
                "enabled": configured and cost > 0,
                "provider_model": model,
                "config": {
                    "provider": "google_gemini",
                    "image_size": size,
                    "aspect_ratios": sorted(ASPECT_RATIOS),
                    "input_modes": ["text", "image_and_text"],
                },
                "unit_cost_paise": cost,
                "overhead_paise": settings.GEMINI_OVERHEAD_PAISE,
                "max_units": 1,
            }
            rate = Rate.objects.filter(pk=slug).first()
            if rate is None:
                Rate.objects.create(slug=slug, revision=1, **values)
                action = "created"
            else:
                changed = any(getattr(rate, key) != value for key, value in values.items())
                for key, value in values.items():
                    setattr(rate, key, value)
                if changed:
                    rate.revision += 1
                    rate.save()
                action = "updated" if changed else "unchanged"
            self.stdout.write(f"{slug}: {action}, {'enabled' if values['enabled'] else 'disabled'}")
        for resolution, cost in settings.GEMINI_VIDEO_RATE_COSTS.items():
            slug = f"gemini-omni-11-{resolution}"
            values = {
                "title": f"Gemini Omni 1.1 Flash {resolution}",
                "kind": "video",
                "enabled": configured and cost > 0,
                "provider_model": "gemini-omni-1.1-flash",
                "config": {
                    "provider": "google_gemini",
                    "resolution": resolution,
                    "aspect_ratios": ["16:9", "9:16"],
                    "durations": [5, 10],
                    "input_modes": ["text", "image_and_text"],
                    "generated_audio": True,
                },
                "unit_cost_paise": cost,
                "overhead_paise": settings.GEMINI_OVERHEAD_PAISE,
                "max_units": 10,
            }
            rate = Rate.objects.filter(pk=slug).first()
            if rate is None:
                Rate.objects.create(slug=slug, revision=1, **values)
                action = "created"
            else:
                changed = any(getattr(rate, key) != value for key, value in values.items())
                for key, value in values.items():
                    setattr(rate, key, value)
                if changed:
                    rate.revision += 1
                    rate.save()
                action = "updated" if changed else "unchanged"
            self.stdout.write(f"{slug}: {action}, {'enabled' if values['enabled'] else 'disabled'}")
