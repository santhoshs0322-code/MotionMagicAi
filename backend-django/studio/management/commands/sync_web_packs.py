from django.conf import settings
from django.core.management.base import BaseCommand
from studio.models import WebCreditPack


class Command(BaseCommand):
    help = "Synchronize web-only PayU credit prices; never edit Google Play products."

    def handle(self, *args, **kwargs):
        for credits, title in ((2500,"Creator"),(5000,"Pro"),(10000,"Studio"),(20000,"Ultimate")):
            amount = settings.WEB_PACK_PRICES[credits]
            WebCreditPack.objects.update_or_create(slug=f"web-{credits}", defaults=dict(title=title+" Pack", credits=credits,
                amount_paise=amount, enabled=settings.PAYU_ENABLED and amount>0))
        self.stdout.write("Web credit packs synchronized independently of Play prices")
