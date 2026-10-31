"""Owner-only browser console. App JWTs and ordinary Django staff grant no access."""
import csv
import io
import ipaddress
from decimal import Decimal
from functools import wraps
from urllib.parse import urlencode

from django import forms
from django.conf import settings
from django.contrib.auth import login, logout
from django.contrib.auth.forms import AuthenticationForm
from django.core.paginator import Paginator
from django.db import IntegrityError, transaction
from django.db.models import Sum
from django.http import Http404, HttpResponse, HttpResponseForbidden, StreamingHttpResponse
from django.shortcuts import get_object_or_404, redirect, render
from django.utils import timezone
from django.views.decorators.cache import never_cache
from django.views.decorators.http import require_http_methods, require_POST

from .authentication import rate_limit
from .errors import Problem
from . import finance, fal_billing
from .models import Account, CashEntry, Job, Ledger, OwnerAudit, Purchase, Rate, Wallet


def allowed(user):
    return bool(settings.OWNER_USERNAME and user.is_authenticated and user.is_active and user.is_superuser and user.username == settings.OWNER_USERNAME)


def owner_only(view):
    @never_cache
    @wraps(view)
    def wrapped(request, *args, **kwargs):
        if not settings.OWNER_USERNAME:
            raise Http404()
        if not request.user.is_authenticated:
            return redirect("owner-login")
        if not allowed(request.user):
            return HttpResponseForbidden("Owner access required.")
        with timezone.override(finance.INDIA):
            return view(request, *args, **kwargs)
    return wrapped


@never_cache
@require_http_methods(["GET", "POST"])
def sign_in(request):
    if not settings.OWNER_USERNAME:
        raise Http404()
    if allowed(request.user):
        return redirect("owner-dashboard")
    form = AuthenticationForm(request, data=request.POST if request.method == "POST" else None)
    if request.method == "POST":
        peer = request.META.get("REMOTE_ADDR", "unknown")
        if settings.PROXY_MODE in ("render", "alb"):
            candidate = request.headers.get("X-Forwarded-For", "").split(",")[-1].strip()
            try: peer = str(ipaddress.ip_address(candidate))
            except ValueError: return HttpResponse("Invalid proxy routing.", status=400)
        try:
            rate_limit("owner-login:" + peer, 10, seconds=300)
        except Problem as error:
            return HttpResponse("Login temporarily unavailable. Retry later.", status=error.status_code)
        if form.is_valid():
            if allowed(form.get_user()):
                login(request, form.get_user())
                request.session.set_expiry(3600)
                OwnerAudit.objects.create(owner=form.get_user(), action="login")
                return redirect("owner-dashboard")
            form.add_error(None, "Unable to sign in with these credentials.")
    return render(request, "owner/login.html", {"form": form})


@owner_only
@require_POST
def sign_out(request):
    OwnerAudit.objects.create(owner=request.user, action="logout")
    logout(request)
    return redirect("owner-login")


def filters_or_error(request):
    try: return finance.period(request.GET), None
    except (ValueError, TypeError): return None, HttpResponse("Invalid date range or user filter. Use YYYY-MM-DD and an existing user ID; maximum 367 days.", status=400)


@owner_only
def dashboard(request):
    filters, error = filters_or_error(request)
    if error is not None: return error
    totals, rows, truncated = finance.summary(filters)
    wallet = Wallet.objects.all()
    pending = Job.objects.exclude(state__in=("succeeded", "failed", "cancelled"))
    if filters["user"]:
        wallet = wallet.filter(account_id=filters["user"])
        pending = pending.filter(account_id=filters["user"])
    balances = wallet.aggregate(balance=Sum("balance_paise"), held=Sum("held_paise"))
    credit_cards = [("Wallet credits charged", totals["charged"]), ("Provider allowance reserved", totals["estimated_provider"]),
                    ("Estimated provider-cost margin", totals["estimated_margin"])]
    cash_cards = [("Reconciled provider cost", totals["billed_provider"]), ("Recorded customer payments", totals["receipt"]),
                  ("Recorded refunds", totals["refund"]), ("Recorded store/payment fees", totals["store_fee"]),
                  ("Other recorded expenses", totals["expense"]), ("Provider credit top-ups", totals["provider_topup"]),
                  ("Recorded net cash movement", totals["cash_net"])]
    return render(request, "owner/dashboard.html", {"filters": filters, "totals": totals, "rows": rows, "truncated": truncated,
        "credit_cards": credit_cards, "cash_cards": cash_cards, "balances": balances, "pending": pending.count(), "query": request.GET.urlencode(),
        "markup": Decimal(settings.MARKUP_BPS) / 100, "billing_configured": fal_billing.configured()})


@owner_only
def records(request, kind):
    filters, error = filters_or_error(request)
    if error is not None: return error
    if kind == "jobs":
        query = finance.job_query(filters).select_related("account", "quote__rate", "cost_record").order_by("-settled_at", "-pk")
    elif kind == "pending":
        query = Job.objects.exclude(state__in=("succeeded", "failed", "cancelled")).select_related("account", "quote__rate", "cost_record").order_by("-created_at", "-pk")
        if filters["user"]: query = query.filter(account_id=filters["user"])
    elif kind == "ledger":
        query = Ledger.objects.filter(created_at__gte=filters["from"], created_at__lt=filters["until"]).select_related("account").order_by("-created_at", "-pk")
        if filters["user"]: query = query.filter(account_id=filters["user"])
    elif kind == "cash":
        query = finance.cash_query(filters).select_related("account", "recorded_by", "reversal").order_by("-occurred_at", "-pk")
    elif kind == "users":
        query = Account.objects.select_related("wallet").order_by("pk")
        term = request.GET.get("search", "")[:100].strip()
        if term: query = query.filter(email__icontains=term)
    elif kind == "audit":
        query = OwnerAudit.objects.select_related("owner").filter(created_at__gte=filters["from"], created_at__lt=filters["until"]).order_by("-created_at", "-pk")
    elif kind == "purchases":
        query = Purchase.objects.defer("token_encrypted", "token_hash").select_related("account", "product").filter(created_at__gte=filters["from"], created_at__lt=filters["until"]).order_by("-created_at", "-pk")
        if filters["user"]: query = query.filter(account_id=filters["user"])
    elif kind == "rates":
        query = Rate.objects.filter(provider_model__startswith="fal:").exclude(provider_model="fal:nano-banana").order_by("pk")
    else: raise Http404()
    params = request.GET.copy(); params.pop("page", None)
    return render(request, "owner/records.html", {"kind": kind, "page": Paginator(query, 50).get_page(request.GET.get("page")), "query": params.urlencode(), "filters": filters})


@owner_only
def job_detail(request, identifier):
    job = get_object_or_404(Job.objects.select_related("account", "quote__rate", "cost_record"), pk=identifier)
    OwnerAudit.objects.create(owner=request.user, action="view_job", reference=str(job.pk))
    return render(request, "owner/job.html", {"job": job, "history": job.cost_history.select_related("recorded_by").order_by("-created_at")[:100],
                                              "entries": Ledger.objects.filter(account=job.account, reference__startswith=f"job:{job.pk}:").order_by("created_at")})


class CashForm(forms.Form):
    occurred_at = forms.DateTimeField(label="Statement date/time (India)", widget=forms.DateTimeInput(attrs={"type": "datetime-local"}), input_formats=["%Y-%m-%dT%H:%M"])
    kind = forms.ChoiceField(choices=CashEntry.KINDS)
    amount = forms.DecimalField(label="Amount in INR (rupees)", min_value=Decimal("0.01"), max_digits=14, decimal_places=2)
    account = forms.CharField(label="App user ID (optional; blank = business-wide)", required=False, max_length=128)
    purchase = forms.UUIDField(label="Purchase ID (optional)", required=False)
    reference = forms.CharField(label="Unique statement/transaction reference", max_length=160)
    note = forms.CharField(label="Evidence / explanation", max_length=500, widget=forms.Textarea(attrs={"rows": 3}))

    def clean(self):
        data = super().clean()
        account = data.get("account")
        if account:
            try: data["account_obj"] = Account.objects.get(pk=account)
            except Account.DoesNotExist: self.add_error("account", "User not found.")
        if data.get("purchase"):
            try:
                purchase = Purchase.objects.get(pk=data["purchase"])
                if account and purchase.account_id != account: self.add_error("purchase", "Purchase belongs to a different user.")
                data["account_obj"], data["purchase_obj"] = purchase.account, purchase
            except Purchase.DoesNotExist: self.add_error("purchase", "Purchase not found.")
        if data.get("reference", "").startswith("reversal:"):
            self.add_error("reference", "This reference prefix is reserved.")
        return data


class CostForm(forms.Form):
    job = forms.UUIDField(label="Generation job ID")
    amount = forms.DecimalField(label="Verified provider cost in INR (rupees)", min_value=0, max_digits=14, decimal_places=2)
    evidence = forms.CharField(label="Invoice or billing evidence reference", max_length=250)


@owner_only
@require_http_methods(["GET", "POST"])
def add_entry(request, kind):
    if kind not in ("cash", "cost"): raise Http404()
    form = (CashForm if kind == "cash" else CostForm)(request.POST if request.method == "POST" else None)
    if request.method == "POST" and form.is_valid():
        data = form.cleaned_data
        try:
            with transaction.atomic():
                if kind == "cash":
                    item = CashEntry.objects.create(occurred_at=data["occurred_at"], kind=data["kind"], amount_paise=int(data["amount"] * 100),
                        account=data.get("account_obj"), purchase=data.get("purchase_obj"), reference=data["reference"], note=data["note"], recorded_by=request.user)
                    OwnerAudit.objects.create(owner=request.user, action="record_cash", reference=str(item.pk))
                else:
                    finance.record_cost(data["job"], int(data["amount"] * 100), data["evidence"], owner=request.user)
            return redirect("owner-records", kind="cash" if kind == "cash" else "jobs")
        except IntegrityError: form.add_error(None, "Reference already exists. No duplicate entry was added.")
        except Job.DoesNotExist: form.add_error("job", "Job not found.")
        except ValueError as exc: form.add_error(None, str(exc))
    return render(request, "owner/form.html", {"form": form, "kind": kind})


@owner_only
@require_POST
def reverse_entry(request, identifier):
    note = request.POST.get("note", "").strip()
    if not 5 <= len(note) <= 500: return HttpResponse("Provide a correction reason of 5–500 characters.", status=400)
    try: finance.reverse_cash(identifier, request.user, note)
    except CashEntry.DoesNotExist: raise Http404()
    except ValueError as exc: return HttpResponse(str(exc), status=409)
    return redirect("owner-records", kind="cash")


def csv_safe(value):
    text = str(value if value is not None else "")
    return "'" + text if text.lstrip().startswith(("=", "+", "-", "@", "\t", "\r", "\n")) else text


@owner_only
def export(request, kind):
    filters, error = filters_or_error(request)
    if error is not None: return error
    if kind == "jobs":
        query = finance.job_query(filters).select_related("account", "quote__rate", "cost_record").order_by("settled_at", "pk")
        headers = ["job", "user_id", "email", "settled_UTC", "model", "status", "provider_request", "charged_credits", "provider_allowance_raw_cents", "reconciled_cost_inr_paise", "cost_source", "billed_USD", "USD_INR"]
        def values(j):
            c = j.cost_record
            return [j.pk, j.account_id, j.account.email, j.settled_at, j.quote.rate_id, j.state, j.provider_id, j.charged_paise, j.actual_cost_paise, c.amount_paise if c else None, c.source if c else "unreconciled", c.original_usd if c else None, c.usd_inr if c else None]
    elif kind == "ledger":
        query = Ledger.objects.filter(created_at__gte=filters["from"], created_at__lt=filters["until"]).order_by("created_at", "pk")
        if filters["user"]: query = query.filter(account_id=filters["user"])
        headers = ["id", "user_id", "time_UTC", "kind", "balance_delta_credits", "held_delta_credits", "balance_after_credits", "held_after_credits", "reference"]
        def values(e): return [e.pk, e.account_id, e.created_at, e.kind, e.balance_delta, e.held_delta, e.balance_after, e.held_after, e.reference]
    elif kind == "cash":
        query = finance.cash_query(filters).order_by("occurred_at", "pk")
        headers = ["id", "time_UTC", "kind", "amount_paise", "user_id", "purchase", "reference", "note", "reversal_of"]
        def values(e): return [e.pk, e.occurred_at, e.kind, e.amount_paise, e.account_id, e.purchase_id, e.reference, e.note, e.reversal_of_id]
    else: raise Http404()
    if query[:10001].count() > 10000:
        return HttpResponse("Export exceeds 10,000 rows. Narrow the date range or filter by user.", status=400)
    OwnerAudit.objects.create(owner=request.user, action="export_" + kind, reference=urlencode({key: filters[key] for key in ("start", "end", "user")})[:250])
    def lines():
        buffer = io.StringIO(); writer = csv.writer(buffer)
        writer.writerow(headers); yield buffer.getvalue(); buffer.seek(0); buffer.truncate(0)
        for row in query.iterator(chunk_size=500):
            writer.writerow([csv_safe(v) for v in values(row)])
            yield buffer.getvalue(); buffer.seek(0); buffer.truncate(0)
    response = StreamingHttpResponse(lines(), content_type="text/csv; charset=utf-8")
    response["Content-Disposition"] = f'attachment; filename="motionmagic-{kind}.csv"'
    return response
