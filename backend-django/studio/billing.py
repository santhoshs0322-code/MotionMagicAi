from urllib.parse import quote
from cryptography.fernet import Fernet
from django.conf import settings
from django.db import transaction
from django.utils import timezone
from google.oauth2 import service_account
from google.auth.transport.requests import AuthorizedSession
from .models import Product, Purchase
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
