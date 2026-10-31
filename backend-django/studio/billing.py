from datetime import datetime
from urllib.parse import quote
from cryptography.fernet import Fernet
from django.conf import settings
from django.db import transaction
from django.utils import timezone
from google.oauth2 import service_account
from google.auth.transport.requests import AuthorizedSession
from .models import Product, Purchase, Subscription
from .authentication import digest
from .services import wallet_for_update, entry
from .errors import Problem

def cipher():
    return Fernet(settings.BILLING_ENCRYPTION_KEY.encode())

def play_request(sku, token, consume=False):
    if not settings.PLAY_ENABLED:
        raise Problem("Purchases are not enabled.", 503)
    credentials = service_account.Credentials.from_service_account_file(settings.PLAY_CREDENTIALS_FILE, scopes=["https://www.googleapis.com/auth/androidpublisher"])
    url = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/" + quote(settings.PLAY_PACKAGE, safe="") + "/purchases/products/" + quote(sku, safe="") + "/tokens/" + quote(token, safe="")
    with AuthorizedSession(credentials) as session:
        response = session.post(url + ":consume", timeout=15, allow_redirects=False) if consume else session.get(url, timeout=15, allow_redirects=False)
        if response.status_code != 200 and not (consume and response.status_code == 204):
            raise Problem("Google Play could not verify this purchase. Retry later.", 503)
        return {} if consume else response.json()

def play_subscription_request(token):
    """Read the current subscription state using Play's v2 endpoint."""
    if not settings.PLAY_ENABLED:
        raise Problem("Purchases are not enabled.", 503)
    credentials = service_account.Credentials.from_service_account_file(settings.PLAY_CREDENTIALS_FILE, scopes=["https://www.googleapis.com/auth/androidpublisher"])
    url = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/" + quote(settings.PLAY_PACKAGE, safe="") + "/purchases/subscriptionsv2/tokens/" + quote(token, safe="")
    with AuthorizedSession(credentials) as session:
        response = session.get(url, timeout=15, allow_redirects=False)
        if response.status_code != 200:
            raise Problem("Google Play could not verify this subscription. Retry later.", 503)
        return response.json()

def acknowledge_subscription(product_id, token):
    """Acknowledge server-side so a completed subscription is not refunded by Play."""
    credentials = service_account.Credentials.from_service_account_file(settings.PLAY_CREDENTIALS_FILE, scopes=["https://www.googleapis.com/auth/androidpublisher"])
    base = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/" + quote(settings.PLAY_PACKAGE, safe="")
    url = base + "/purchases/subscriptions/" + quote(product_id, safe="") + "/tokens/" + quote(token, safe="") + ":acknowledge"
    with AuthorizedSession(credentials) as session:
        response = session.post(url, json={}, timeout=15, allow_redirects=False)
        # An already acknowledged purchase is safe. Do not reverse a verified
        # wallet grant merely because a transient acknowledgement retry failed.
        if response.status_code not in (200, 204, 409):
            raise Problem("Google Play could not acknowledge this subscription. Retry later.", 503)

def _time(value):
    if not isinstance(value, str):
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None

SUBSCRIPTIONS = {
    "motion_creator_monthly": ("creator", 1200),
    "motion_legend_monthly": ("legend", 2400),
}
STATE_MAP = {
    "SUBSCRIPTION_STATE_ACTIVE": "active",
    "SUBSCRIPTION_STATE_IN_GRACE_PERIOD": "grace",
    "SUBSCRIPTION_STATE_CANCELED": "cancelled",
    "SUBSCRIPTION_STATE_ON_HOLD": "on_hold",
    "SUBSCRIPTION_STATE_EXPIRED": "expired",
    "SUBSCRIPTION_STATE_REVOKED": "revoked",
}

def _subscription_details(receipt, product_id):
    lines = receipt.get("lineItems")
    if not isinstance(lines, list):
        raise Problem("Google Play returned an invalid subscription.", 503)
    line = next((row for row in lines if isinstance(row, dict) and row.get("productId") == product_id), None)
    if line is None:
        raise Problem("Subscription product does not match this purchase.", 403)
    identifiers = receipt.get("externalAccountIdentifiers") or {}
    state = STATE_MAP.get(receipt.get("subscriptionState"))
    if state is None:
        raise Problem("Google Play returned an unsupported subscription state.", 503)
    renew = line.get("autoRenewingPlan")
    return state, _time(line.get("expiryTime")), bool(isinstance(renew, dict)), identifiers.get("obfuscatedExternalAccountId"), receipt.get("latestOrderId", "")

def verify_subscription(account, product_id, token):
    if not settings.SUBSCRIPTIONS_ENABLED:
        raise Problem("Subscriptions are not enabled yet.", 503)
    if product_id not in SUBSCRIPTIONS or not isinstance(token, str) or not 10 <= len(token) <= 4096:
        raise Problem("Invalid subscription request.")
    plan, credits = SUBSCRIPTIONS[product_id]
    receipt = play_subscription_request(token)
    state, expires_at, auto_renewing, external_id, order_id = _subscription_details(receipt, product_id)
    if external_id != digest(str(account.pk)):
        raise Problem("Subscription account binding is invalid.", 403)
    if not isinstance(order_id, str) or not order_id or len(order_id) > 200:
        raise Problem("Google Play returned an invalid subscription order.", 503)
    token_hash = digest(token)
    with transaction.atomic():
        wallet = wallet_for_update(account)
        subscription = Subscription.objects.select_for_update().filter(token_hash=token_hash).first()
        if subscription is not None and subscription.account_id != str(account.pk):
            raise Problem("Subscription belongs to another account.", 409)
        if subscription is None:
            subscription = Subscription(account=account, product_id=product_id, token_hash=token_hash,
                token_encrypted=cipher().encrypt(token.encode()).decode(), plan=plan)
        elif subscription.product_id != product_id:
            raise Problem("Subscription product does not match this purchase.", 409)
        subscription.state = state
        subscription.expires_at = expires_at
        subscription.auto_renewing = auto_renewing
        subscription.latest_order_id = order_id
        subscription.checked_at = timezone.now()
        # An order ID is immutable for the payment period.  This makes retries,
        # app reinstalls and delivery duplicates unable to double grant credits.
        grant_key = f"subscription:{token_hash}:{order_id}"
        grantable = state in ("active", "grace", "cancelled") and (expires_at is None or expires_at > timezone.now())
        if grantable and subscription.last_grant_key != grant_key:
            entry(wallet, "subscription", grant_key, balance_delta=credits)
            subscription.last_grant_key = grant_key
        subscription.save()
    try:
        acknowledge_subscription(product_id, token)
    except Problem:
        # A future app launch / RTDN retry calls verification again. The grant
        # key is idempotent, so retrying acknowledgement cannot add credits.
        pass
    return subscription

def reconcile_subscription(subscription):
    """Refresh a known subscription after a Play RTDN or app recovery."""
    token = cipher().decrypt(subscription.token_encrypted.encode()).decode()
    return verify_subscription(subscription.account, subscription.product_id, token)

def verify_purchase(account, sku, token):
    if not settings.PLAY_ENABLED:
        raise Problem("Purchases are not enabled.", 503)
    if not isinstance(token, str) or not 10 <= len(token) <= 4096 or not isinstance(sku, str):
        raise Problem("Invalid purchase request.")
    existing = Purchase.objects.filter(token_hash=digest(token)).first()
    if existing:
        if existing.account_id != str(account.pk) or existing.product_id != sku:
            raise Problem("Purchase belongs to another account or product.", 409)
        # Still verify current store state; a previously credited purchase can be refunded.
        reconcile_purchase(existing)
        existing.refresh_from_db()
        return existing
    try:
        product = Product.objects.get(pk=sku, enabled=True, credit_paise__gt=0)
    except Product.DoesNotExist:
        raise Problem("Product is unavailable.", 404) from None
    receipt = play_request(sku, token)
    if receipt.get("purchaseState") == 2:
        raise Problem("Purchase is pending. No credits have been granted.", 409)
    if receipt.get("purchaseState") != 0 or receipt.get("consumptionState") != 0 or receipt.get("quantity", 1) != 1:
        raise Problem("Purchase is cancelled, consumed or has an unsupported quantity.", 409)
    if receipt.get("obfuscatedExternalAccountId") != digest(str(account.pk)) or receipt.get("productId", sku) != sku:
        raise Problem("Purchase account binding is invalid.", 403)
    order = receipt.get("orderId")
    if not isinstance(order, str) or not 1 <= len(order) <= 200:
        raise Problem("Store returned an invalid order.", 503)
    with transaction.atomic():
        wallet = wallet_for_update(account)
        duplicate = Purchase.objects.filter(token_hash=digest(token)).first()
        if duplicate:
            if duplicate.account_id != str(account.pk) or duplicate.product_id != sku:
                raise Problem("Purchase is already assigned.", 409)
            return duplicate
        purchase = Purchase.objects.create(account=account, product=product, token_hash=digest(token), token_encrypted=cipher().encrypt(token.encode()).decode(), order_id=order, credit_paise=product.credit_paise, checked_at=timezone.now())
        entry(wallet, "purchase", f"purchase:{purchase.pk}:grant", balance_delta=product.credit_paise)
    # Credits commit before consume. Failed consumption retries never grant again.
    try:
        play_request(sku, token, consume=True)
        Purchase.objects.filter(pk=purchase.pk).update(consumed=True)
        purchase.consumed = True
    except Problem:
        pass
    return purchase

def reconcile_purchase(purchase):
    token = cipher().decrypt(purchase.token_encrypted.encode()).decode()
    receipt = play_request(purchase.product_id, token)
    state = receipt.get("purchaseState")
    if state == 1:
        with transaction.atomic():
            wallet = wallet_for_update(purchase.account)
            current = Purchase.objects.select_for_update().get(pk=purchase.pk)
            if current.state != "refunded":
                entry(wallet, "refund", f"purchase:{current.pk}:refund", balance_delta=-current.credit_paise)
                current.state = "refunded"
                current.checked_at = timezone.now()
                current.save(update_fields=["state", "checked_at"])
    elif state == 0:
        consumed = receipt.get("consumptionState") == 1
        if not consumed:
            play_request(purchase.product_id, token, consume=True)
        Purchase.objects.filter(pk=purchase.pk).update(consumed=True, checked_at=timezone.now())
    else:
        raise Problem("Store purchase state is unresolved.", 503)
