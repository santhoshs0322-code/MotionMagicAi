import uuid
from django.db import models
from django.db.models import Q

class Account(models.Model):
    # String IDs preserve existing Mongo IDs when importing only MotionMagic accounts.
    id = models.CharField(primary_key=True, max_length=128, default=uuid.uuid4, editable=False)
    google_sub = models.CharField(max_length=255, unique=True)
    email = models.EmailField(max_length=320)
    name = models.CharField(max_length=200)
    picture = models.URLField(max_length=2048, blank=True)
    is_active = models.BooleanField(default=True)
    token_version = models.PositiveIntegerField(default=0)
    created_at = models.DateTimeField(auto_now_add=True, db_index=True)
    @property
    def is_authenticated(self):
        return self.is_active

class Revocation(models.Model):
    jti_hash = models.CharField(primary_key=True, max_length=64)
    expires_at = models.DateTimeField(db_index=True)

class Wallet(models.Model):
    account = models.OneToOneField(Account, on_delete=models.PROTECT, primary_key=True)
    # A store refund after spending may create debt; it never erases the audit trail.
    balance_paise = models.BigIntegerField(default=0)
    held_paise = models.PositiveBigIntegerField(default=0)
    @property
    def available_paise(self):
        return self.balance_paise - self.held_paise

class Ledger(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    account = models.ForeignKey(Account, on_delete=models.PROTECT)
    kind = models.CharField(max_length=20)
    balance_delta = models.BigIntegerField(default=0)
    held_delta = models.BigIntegerField(default=0)
    balance_after = models.BigIntegerField()
    held_after = models.PositiveBigIntegerField()
    reference = models.CharField(max_length=160, unique=True)
    created_at = models.DateTimeField(auto_now_add=True)
    class Meta:
        indexes = [models.Index(fields=["account", "-created_at", "-id"])]

class Rate(models.Model):
    slug = models.SlugField(primary_key=True)
    title = models.CharField(max_length=100)
    kind = models.CharField(max_length=12, choices=[("image", "Image"), ("video", "Video"), ("voice", "Voice")])
    enabled = models.BooleanField(default=False)
    provider_model = models.CharField(max_length=120)
    # Unit = one image, one video second, or 1,000 voice characters (rounded up).
    unit_cost_paise = models.PositiveIntegerField()
    overhead_paise = models.PositiveIntegerField(default=0)
    max_units = models.PositiveIntegerField(default=1)
    revision = models.PositiveIntegerField(default=1)
    updated_at = models.DateTimeField(auto_now=True)

class Asset(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    account = models.ForeignKey(Account, on_delete=models.PROTECT)
    key = models.CharField(max_length=300, unique=True)
    mime = models.CharField(max_length=64)
    size = models.PositiveBigIntegerField()
    ready = models.BooleanField(default=False)
    deleted = models.BooleanField(default=False)
    created_at = models.DateTimeField(auto_now_add=True)
    class Meta:
        indexes = [models.Index(fields=["account", "-created_at"])]

class Quote(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    account = models.ForeignKey(Account, on_delete=models.PROTECT)
    rate = models.ForeignKey(Rate, on_delete=models.PROTECT)
    payload = models.JSONField()
    payload_hash = models.CharField(max_length=64)
    provider_cost_paise = models.PositiveIntegerField()
    overhead_paise = models.PositiveIntegerField()
    markup_bps = models.PositiveIntegerField()
    fee_bps = models.PositiveIntegerField()
    charge_paise = models.PositiveIntegerField()
    expires_at = models.DateTimeField(db_index=True)
    created_at = models.DateTimeField(auto_now_add=True)

class Budget(models.Model):
    day = models.DateField()
    shard = models.PositiveSmallIntegerField()
    reserved_paise = models.PositiveBigIntegerField(default=0)
    spent_paise = models.PositiveBigIntegerField(default=0)
    class Meta:
        constraints = [models.UniqueConstraint(fields=["day", "shard"], name="budget_day_shard"), models.CheckConstraint(condition=Q(shard__lt=64), name="budget_valid_shard")]

class Job(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    account = models.ForeignKey(Account, on_delete=models.PROTECT)
    quote = models.OneToOneField(Quote, on_delete=models.PROTECT)
    idempotency_key = models.CharField(max_length=128)
    state = models.CharField(max_length=16, default="queued")
    provider_id = models.CharField(max_length=200, blank=True)
    output = models.ForeignKey(Asset, on_delete=models.PROTECT, null=True)
    budget = models.ForeignKey(Budget, on_delete=models.PROTECT)
    actual_cost_paise = models.PositiveIntegerField(null=True)
    charged_paise = models.PositiveIntegerField(null=True)
    error_code = models.CharField(max_length=40, blank=True)
    next_attempt_at = models.DateTimeField(db_index=True)
    lease_until = models.DateTimeField(null=True)
    lease_id = models.UUIDField(null=True)
    attempts = models.PositiveIntegerField(default=0)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)
    class Meta:
        constraints = [models.UniqueConstraint(fields=["account", "idempotency_key"], name="job_account_idempotency"), models.CheckConstraint(condition=Q(state__in=("queued", "submitting", "running", "uncertain", "succeeded", "failed", "cancelled")), name="job_valid_state")]
        indexes = [models.Index(fields=["state", "next_attempt_at"]), models.Index(fields=["account", "-created_at", "-id"])]

class Product(models.Model):
    sku = models.CharField(primary_key=True, max_length=150)
    title = models.CharField(max_length=100)
    credit_paise = models.PositiveIntegerField()
    enabled = models.BooleanField(default=False)
    # Credit packs only. Subscriptions must not appear until their lifecycle is implemented.

class Purchase(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    account = models.ForeignKey(Account, on_delete=models.PROTECT)
    product = models.ForeignKey(Product, on_delete=models.PROTECT)
    token_hash = models.CharField(max_length=64, unique=True)
    token_encrypted = models.TextField()
    order_id = models.CharField(max_length=200, unique=True)
    credit_paise = models.PositiveIntegerField()
    state = models.CharField(max_length=16, default="granted")
    consumed = models.BooleanField(default=False)
    checked_at = models.DateTimeField(null=True, db_index=True)
    created_at = models.DateTimeField(auto_now_add=True)
    class Meta:
        indexes = [models.Index(fields=["account", "-created_at", "-id"])]
        constraints = [models.CheckConstraint(condition=Q(state__in=("granted", "refunded")), name="purchase_valid_state")]
