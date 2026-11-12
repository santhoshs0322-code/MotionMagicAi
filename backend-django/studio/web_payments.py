"""PayU hosted checkout adapted from the existing backend-auth/payu.js contract.

Only independently verified payments can change MotionMagic's shared credit wallet.
"""
import hashlib
import re
import secrets
import time
from datetime import timedelta
from decimal import Decimal
from urllib.parse import urlsplit

import requests
from django.conf import settings
from django.db import IntegrityError, transaction
from django.utils import timezone

from . import services
from .errors import Problem
from .models import CashEntry, WebCreditPack, WebPayment


def origin():
    value = settings.WEB_PUBLIC_URL or settings.CURRENT_SITE_URL
    parsed = urlsplit(value)
    if parsed.scheme != "https" or not parsed.netloc or parsed.username or parsed.password or parsed.path not in ("", "/") or parsed.query or parsed.fragment:
        raise Problem("Configure the HTTPS web payment origin.", 503)
    return value.rstrip("/")


def require_config(mode=None, sales=False):
    if not settings.PAYU_KEY or not settings.PAYU_SALT:
        raise Problem("Web payments are not enabled yet.", 503)
    if sales and (not settings.WEB_ENABLED or not settings.PAYU_ENABLED):
        raise Problem("Web payments are not enabled yet.", 503)
    if sales and settings.PAYU_MODE == "live" and not settings.PAYU_ALLOW_LIVE:
        raise Problem("Live PayU payments are not enabled.", 503)
    if mode and mode != settings.PAYU_MODE:
        raise Problem("This order belongs to a different payment environment.", 409)
    if sales:
        origin()


def money(paise):
    return f"{Decimal(paise)/100:.2f}"


def paise(value):
    if not re.fullmatch(r"[0-9]{1,10}(?:\.[0-9]{1,2})?", str(value)):
        raise Problem("PayU returned an invalid amount.", 409)
    return int(Decimal(str(value))*100)


def sha512(parts):
    return hashlib.sha512("|".join(str(value) for value in parts).encode()).hexdigest()


def request_hash(fields):
    return sha512([fields.get(k, "") for k in ("key", "txnid", "amount", "productinfo", "firstname", "email", "udf1", "udf2", "udf3", "udf4", "udf5")] + [""]*5 + [settings.PAYU_SALT])


def response_hash(fields):
    parts = [settings.PAYU_SALT, fields.get("status", "")] + [""]*5 + [fields.get(k, "") for k in ("udf5", "udf4", "udf3", "udf2", "udf1", "email", "firstname", "productinfo", "amount", "txnid", "key")]
    extra = fields.get("additionalCharges", fields.get("additional_charges"))
    return sha512(([extra] if extra is not None else []) + parts)


def valid_callback(fields):
    provided = fields.get("hash", "")
    return fields.get("key") == settings.PAYU_KEY and not fields.get("splitInfo") and bool(re.fullmatch(r"[0-9a-fA-F]{128}", provided)) and secrets.compare_digest(provided.lower(), response_hash(fields))


def public(order):
    return dict(id=str(order.pk), pack=order.pack_id, credits=order.credits, amount_paise=order.amount_paise,
                currency="INR", state=order.state, test=order.mode=="test", refunded_paise=order.refunded_paise, created_at=order.created_at,
                expired=order.expires_at<=timezone.now(),
                checkout_url=f"/app/payments/{order.pk}/checkout/" if order.state=="pending" and order.expires_at>timezone.now() else None)


def create_order(account, data):
    require_config(sales=True)
    if not isinstance(data, dict) or set(data) != {"pack", "request_key", "phone"}:
        raise Problem("Choose a credit pack and enter your phone number. Prices are set by the server.")
    key, phone = data["request_key"], data["phone"]
    if not isinstance(key, str) or not re.fullmatch(r"[A-Za-z0-9_-]{8,80}", key) or not isinstance(phone, str) or not re.fullmatch(r"\+?[0-9]{10,15}", phone):
        raise Problem("Enter a valid purchase request and phone number.")
    previous = WebPayment.objects.filter(account=account, request_key=key).first()
    if previous:
        if previous.pack_id != data["pack"]:
            raise Problem("Retry with the original pack and purchase request.", 409)
        return previous
    pack = WebCreditPack.objects.filter(pk=data["pack"], enabled=True, amount_paise__gt=0).first()
    if not pack:
        raise Problem("This web credit pack is not available.", 409)
    name = (account.name.split()[0] if account.name.strip() else "Creator")[:60]
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", str(account.pk)) or any(c in name+account.email for c in "|\r\n\x00"):
        raise Problem("Contact information is not valid for PayU.")
    try:
        with transaction.atomic():
            return WebPayment.objects.create(account=account, pack=pack, request_key=key, amount_paise=pack.amount_paise,
                credits=pack.credits, mode=settings.PAYU_MODE, firstname=name, email=account.email, phone=phone,
                expires_at=timezone.now()+timedelta(minutes=30))
    except IntegrityError:
        previous = WebPayment.objects.filter(account=account, request_key=key, pack=pack).first()
        if previous:
            return previous
        raise Problem("Purchase request conflict. Retry the original request.", 409) from None


def checkout_fields(order):
    require_config(order.mode, sales=True)
    if order.state != "pending" or order.expires_at <= timezone.now():
        raise Problem("Checkout expired or already completed. Return to your credit packs.", 409)
    fields = dict(key=settings.PAYU_KEY, txnid=order.pk.hex, amount=money(order.amount_paise),
        productinfo=f"MotionMagic AI {order.credits} credits", firstname=order.firstname, email=order.email,
        phone=order.phone, udf1=str(order.account_id), udf2=order.pack_id, udf3=order.mode, udf4="", udf5="",
        surl=origin()+"/app/payments/payu/return/", furl=origin()+"/app/payments/payu/return/")
    fields["hash"] = request_hash(fields)
    return fields


def api(command, txnid):
    url = "https://info.payu.in/merchant/postservice.php?form=2" if settings.PAYU_MODE=="live" else "https://test.payu.in/merchant/postservice.php?form=2"
    data = dict(key=settings.PAYU_KEY, command=command, var1=txnid,
                hash=sha512([settings.PAYU_KEY, command, txnid, settings.PAYU_SALT]))
    try:
        with requests.post(url, data=data, timeout=(3, 10), allow_redirects=False, stream=True) as response:
            response.raise_for_status()
            content = bytearray()
            deadline = time.monotonic()+20
            for chunk in response.iter_content(16384):
                content.extend(chunk)
                if len(content) > 512*1024 or time.monotonic()>deadline:
                    raise ValueError("large")
            import json
            value = json.loads(content)
            if not isinstance(value, dict):
                raise ValueError("shape")
            return value
    except Exception:
        raise Problem("PayU verification is temporarily unavailable. Your payment remains recoverable.", 503) from None


def verified_refunds(order, payment_id):
    result = api("getAllRefundsFromTxnIds", order.pk.hex)
    if str(result.get("status")) != "1":
        raise Problem("PayU refund verification is unavailable.", 503)
    details = result.get("Refund Details")
    if details is None:
        if str(result.get("msg", "")).lower().startswith("no refunds found"):
            return 0
        raise Problem("PayU refund response is incomplete.", 503)
    if not isinstance(details, dict) or set(details)-{payment_id} or not isinstance(details.get(payment_id), list):
        raise Problem("PayU refund response does not match the order.", 409)
    total, identifiers = 0, set()
    for row in details[payment_id]:
        if not isinstance(row, dict) or str(row.get("PayuID")) != payment_id:
            raise Problem("PayU refund response is invalid.", 409)
        identifier = str(row.get("RequestID", ""))
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", identifier) or identifier in identifiers:
            raise Problem("PayU refund identifiers are invalid.", 409)
        identifiers.add(identifier)
        if str(row.get("Status", "")).lower() == "success":
            total += paise(row.get("Amount"))
    if total > order.amount_paise:
        raise Problem("PayU refund exceeds the original payment.", 409)
    return total


def reconcile(order):
    require_config(order.mode)
    now = timezone.now()
    if not WebPayment.objects.filter(pk=order.pk, check_after__lte=now).update(check_after=now+timedelta(seconds=30)):
        return WebPayment.objects.get(pk=order.pk)
    details = api("verify_payment", order.pk.hex).get("transaction_details")
    result = details.get(order.pk.hex) if isinstance(details, dict) else None
    if not isinstance(result, dict):
        raise Problem("PayU verification did not return this transaction.", 503)
    captured = result.get("status")=="success" and result.get("unmappedstatus")=="captured"
    if not captured and not order.paid_at:
        state = "failed" if result.get("status") in ("failure", "failed") else "pending"
        WebPayment.objects.filter(pk=order.pk, paid_at__isnull=True).update(state=state, check_after=now+timedelta(minutes=5))
        return WebPayment.objects.get(pk=order.pk)
    payment_id = str(result.get("mihpayid", ""))
    if not re.fullmatch(r"[0-9]{1,64}", payment_id) or result.get("txnid") != order.pk.hex or paise(result.get("amt", result.get("amount"))) != order.amount_paise:
        raise Problem("Verified payment does not match this order.", 409)
    expected = dict(udf1=str(order.account_id), udf2=order.pack_id, udf3=order.mode,
                    productinfo=f"MotionMagic AI {order.credits} credits", email=order.email)
    if any(result.get(k) != value for k, value in expected.items()) or result.get("currency", "INR") != "INR":
        raise Problem("Verified payment account or product does not match.", 409)
    refunded = verified_refunds(order, payment_id)
    with transaction.atomic():
        wallet = services.wallet_for_update(order.account)
        saved = WebPayment.objects.select_for_update().get(pk=order.pk)
        if saved.payment_id and saved.payment_id != payment_id:
            raise Problem("Payment identity changed.", 409)
        if WebPayment.objects.filter(mode=saved.mode, payment_id=payment_id).exclude(pk=order.pk).exists():
            raise Problem("This payment has already been used.", 409)
        if saved.paid_at is None and saved.mode=="live":
            services.entry(wallet, "purchase", f"payu:{saved.pk}", balance_delta=saved.credits, topup_balance_delta=saved.credits)
            CashEntry.objects.get_or_create(reference=f"payu:receipt:{saved.pk}", defaults=dict(account=saved.account,
                occurred_at=now, kind="receipt", amount_paise=saved.amount_paise, note="PayU verified web credit purchase. Fees/taxes need statement reconciliation."))
        if refunded > saved.refunded_paise:
            revoked = (saved.credits*refunded+saved.amount_paise-1)//saved.amount_paise
            if saved.mode=="live":
                services.entry(wallet, "refund", f"payu-refund:{saved.pk}:{refunded}",
                    balance_delta=-(revoked-saved.revoked_credits), topup_balance_delta=-(revoked-saved.revoked_credits))
                CashEntry.objects.get_or_create(reference=f"payu:refund:{saved.pk}:{refunded}", defaults=dict(account=saved.account,
                    occurred_at=now, kind="refund", amount_paise=refunded-saved.refunded_paise, note="PayU server-verified cumulative refund."))
            saved.refunded_paise, saved.revoked_credits = refunded, revoked
        saved.payment_id = payment_id
        saved.paid_at = saved.paid_at or now
        saved.state = "refunded" if saved.refunded_paise==saved.amount_paise else "paid"
        saved.check_after = now+timedelta(hours=12)
        saved.save()
        return saved


def reconcile_recent(limit=5):
    if not settings.PAYU_KEY or not settings.PAYU_SALT:
        return
    from django.db.models import Q
    now = timezone.now()
    rows = WebPayment.objects.filter(mode=settings.PAYU_MODE, check_after__lte=now).filter(
        Q(paid_at__isnull=False, created_at__gte=now-timedelta(days=180)) |
        Q(paid_at__isnull=True, created_at__gte=now-timedelta(days=7))).order_by("check_after", "id")[:limit]
    for row in rows:
        try:
            reconcile(row)
        except Problem:
            continue
