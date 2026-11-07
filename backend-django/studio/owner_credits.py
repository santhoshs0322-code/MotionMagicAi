"""Owner-only, audited bonus credits. A grant is not a payment or a store purchase."""
import uuid

from django import forms
from django.core import signing
from django.core.exceptions import PermissionDenied
from django.db import IntegrityError, transaction
from django.http import HttpResponse
from django.shortcuts import get_object_or_404, redirect, render
from django.views.decorators.http import require_GET, require_http_methods

from . import services
from .models import Account, Ledger, OwnerAudit, OwnerCreditGrant, Wallet
from .owner import allowed, owner_only

REVIEW_SALT = "motion-ai-owner-credit-grant-v1"


class GrantForm(forms.Form):
    account = forms.CharField(label="App user ID", max_length=128,
        help_text="Find the user on Users and select Add credits to fill this automatically.")
    credits = forms.IntegerField(label="Credits to add", min_value=1, max_value=1_000_000)
    note = forms.CharField(label="Reason", min_length=5, max_length=500, widget=forms.Textarea(attrs={"rows": 3}))
    grant_id = forms.UUIDField(widget=forms.HiddenInput)

    def clean_account(self):
        identifier = self.cleaned_data["account"]
        try:
            account = Account.objects.get(pk=identifier)
        except Account.DoesNotExist:
            raise forms.ValidationError("User not found. Choose an existing app user.") from None
        if not account.is_active:
            raise forms.ValidationError("This account is inactive. Credits cannot be granted.")
        return account


def matches(grant, owner, data):
    return (grant.account_id, grant.credits, grant.note, grant.recorded_by_id) == (
        str(data["account"]), data["credits"], data["note"], owner.pk)


@transaction.atomic
def apply_grant(owner, data):
    if not allowed(owner):
        raise PermissionDenied()
    # PostgreSQL's weaker lock permits concurrent ledger FK inserts while we wait for the wallet.
    account = Account.objects.select_for_update(no_key=True).get(pk=data["account"])
    if not account.is_active:
        raise ValueError("This account is inactive. Credits cannot be granted.")
    existing = OwnerCreditGrant.objects.filter(pk=data["grant_id"]).first()
    if existing:
        if not matches(existing, owner, data):
            raise ValueError("This grant reference was already used for different details.")
        return existing
    if type(data["credits"]) is not int or not 1 <= data["credits"] <= 1_000_000 or not 5 <= len(data["note"]) <= 500:
        raise ValueError("Enter 1–1,000,000 whole credits and a reason of 5–500 characters.")
    wallet = services.wallet_for_update(account)
    reference = f"owner-credit:{data['grant_id']}"
    if Ledger.objects.filter(reference=reference).exists():
        raise ValueError("This grant reference has already been used.")
    # Free/bonus bucket: non-expiring, consumed before purchased top-ups.
    services.entry(wallet, "owner_credit", reference, balance_delta=data["credits"])
    ledger = Ledger.objects.get(reference=reference, account=account)
    grant = OwnerCreditGrant.objects.create(pk=data["grant_id"], account=account, credits=data["credits"],
        note=data["note"], ledger=ledger, recorded_by=owner)
    OwnerAudit.objects.create(owner=owner, action="grant_credits", reference=str(grant.pk))
    return grant


@owner_only
@require_http_methods(["GET", "POST"])
def grant_credits(request):
    form = GrantForm(initial={"account": request.GET.get("user", "")[:128], "grant_id": uuid.uuid4()})
    error = ""
    if request.method == "POST":
        action = request.POST.get("action")
        if action == "review":
            form = GrantForm(request.POST)
            if form.is_valid():
                clean = form.cleaned_data
                account = clean["account"]
                data = {"account": str(account.pk), "credits": clean["credits"], "note": clean["note"],
                        "grant_id": str(clean["grant_id"]), "owner": request.user.pk}
                token = signing.dumps(data, salt=REVIEW_SALT)
                return render(request, "owner/credit_review.html", {"account": account, "credits": clean["credits"],
                    "note": clean["note"], "review_token": token, "wallet": Wallet.objects.filter(account=account).first()})
        elif action == "confirm":
            try:
                token = request.POST.get("review_token", "")
                if len(token) > 5000:
                    raise signing.BadSignature()
                data = signing.loads(token, salt=REVIEW_SALT, max_age=600)
                if not isinstance(data, dict) or data.get("owner") != request.user.pk:
                    raise signing.BadSignature()
                form = GrantForm(data)
                if form.is_valid():
                    clean = form.cleaned_data
                    data.update(account=str(clean["account"].pk), credits=clean["credits"], note=clean["note"], grant_id=clean["grant_id"])
                    try:
                        grant = apply_grant(request.user, data)
                    except IntegrityError:
                        # A racing duplicate must roll back its wallet write before reading the receipt.
                        grant = OwnerCreditGrant.objects.filter(pk=data["grant_id"]).first()
                        if grant is None or not matches(grant, request.user, data):
                            raise ValueError("This grant reference is already used. No additional credits were added.") from None
                    return redirect("owner-credit-receipt", identifier=grant.pk)
            except signing.BadSignature:
                form = GrantForm(initial={"grant_id": uuid.uuid4()})
                error = "This review expired or was changed. Review the grant again."
            except (ValueError, Account.DoesNotExist) as failure:
                error = str(failure)
        else:
            return HttpResponse("Review the grant before confirming it.", status=400)
    return render(request, "owner/credit_form.html", {"form": form, "error": error})


@owner_only
@require_GET
def receipt(request, identifier):
    grant = get_object_or_404(OwnerCreditGrant.objects.select_related("account", "recorded_by", "ledger"), pk=identifier)
    return render(request, "owner/credit_receipt.html", {"grant": grant, "wallet": Wallet.objects.get(account=grant.account)})
