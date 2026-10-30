from django.conf import settings
from django.core.management import call_command
from django.core.management.base import BaseCommand

from studio import fal, fal_catalog
from studio.models import Rate


class Command(BaseCommand):
    help = "Sync Google and fal rates; a missing key, storage, budget or price keeps them disabled."

    def handle(self, *args, **options):
        call_command("sync_gemini_rates", stdout=self.stdout)
        configured = fal.configured() and settings.DAILY_PROVIDER_BUDGET_PAISE > 0
        for slug, model, config, _ in fal_catalog.variants():
            video = "resolution" in config
            cost = settings.FAL_RATE_COSTS.get(slug, 0)
            values = dict(title=f"{model['title']} {config.get('resolution', config.get('image_size'))}" + (" + audio" if config.get("generated_audio") else ""),
                          kind="video" if video else "image", enabled=bool(configured and cost > 0),
                          provider_model="fal:" + config["app_model_id"], config=config,
                          unit_cost_paise=cost, overhead_paise=settings.FAL_OVERHEAD_PAISE,
                          max_units=max(config["durations"]) if video else 1)
            rate = Rate.objects.filter(pk=slug).first()
            if rate is None:
                Rate.objects.create(slug=slug, revision=1, **values)
            elif any(getattr(rate, key) != value for key, value in values.items()):
                for key, value in values.items():
                    setattr(rate, key, value)
                rate.revision += 1
                rate.save()
            self.stdout.write(f"{slug}: {'enabled' if values['enabled'] else 'disabled'}")
