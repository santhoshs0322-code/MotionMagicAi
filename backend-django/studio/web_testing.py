"""Web trial limits use the account wallet lock and existing job settlement state."""
from django.conf import settings
from django.db.models import Case, When, F, Sum, Value, BigIntegerField

from .models import WebGeneration


def is_browser(request):
    return isinstance(request.auth, dict) and bool(request.auth.get("web_session"))


def allowance(account):
    rows = WebGeneration.objects.filter(job__account=account, testing=True)
    totals = rows.aggregate(
        spent=Sum(Case(When(job__state="succeeded", then=F("job__charged_paise")), default=Value(0), output_field=BigIntegerField())),
        held=Sum(Case(When(job__state__in=("queued", "submitting", "running", "uncertain"), then=F("job__quote__charge_paise")), default=Value(0), output_field=BigIntegerField())))
    spent, held = totals["spent"] or 0, totals["held"] or 0
    return {"limit": settings.WEB_WELCOME_CREDITS, "spent": spent, "held": held,
        "available": max(0, settings.WEB_WELCOME_CREDITS-spent-held)}
