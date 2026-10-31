from django.conf import settings
from django.core.management import call_command
from django.core.management.base import BaseCommand

from studio import fal, fal_catalog
from studio.models import Product, Rate


class Command(BaseCommand):
    help = "Sync Google and fal rates; a missing key, storage, budget or price keeps them disabled."

    def handle(self, *args, **options):
        call_command("sync_gemini_rates", stdout=self.stdout)
        configured = fal.configured() and settings.DAILY_PROVIDER_BUDGET_PAISE > 0
        for slug, model, config, _ in fal_catalog.variants():
            video = "resolution" in config
            cost = settings.FAL_RATE_COSTS.get(slug, 0)
            values = dict(title=f"{model['title']} {config.get('resolution', config.get('image_size'))}" + (" + audio" if config.get("generated_audio") else ""),
                          kind="video" if video else "image", enabled=bool(configured and cost > 0 and (not settings.FAL_ONLY or "fal:" + config["app_model_id"] in fal_catalog.LAUNCH_MODELS)),
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
        # Creator and Legend are recurring subscriptions, not consumable products.
        Product.objects.filter(pk__in=("motion_creator_credits", "motion_legend_credits")).update(enabled=False)
        enabled_topup = settings.PLAY_ENABLED and (settings.ENABLE_TOPUPS or settings.ENABLE_CREDIT_PACKS)
        Product.objects.filter(pk="motion_topup_500").update(enabled=enabled_topup)
        self.stdout.write(f"permanent 500-credit top-up: {'enabled' if enabled_topup else 'disabled'}")
