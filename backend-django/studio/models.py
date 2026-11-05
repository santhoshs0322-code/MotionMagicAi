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

class RefreshSession(models.Model):
    token_hash = models.CharField(primary_key=True, max_length=64)
    account = models.ForeignKey(Account, on_delete=models.PROTECT)
    token_version = models.PositiveIntegerField()
    expires_at = models.DateTimeField(db_index=True)
    revoked_at = models.DateTimeField(null=True)
    created_at = models.DateTimeField(auto_now_add=True)

class Wallet(models.Model):
    account = models.OneToOneField(Account, on_delete=models.PROTECT, primary_key=True)
    # Legacy database names. Values are integer customer credits, never INR paise.
    # A store refund after spending may create debt; it never erases the audit trail.
    balance_paise = models.BigIntegerField(default=0)
    held_paise = models.PositiveBigIntegerField(default=0)
    monthly_balance_paise = models.PositiveBigIntegerField(default=0)
    monthly_held_paise = models.PositiveBigIntegerField(default=0)
    monthly_period_key = models.CharField(max_length=160, blank=True)
    monthly_expires_at = models.DateTimeField(null=True)
    topup_balance_paise = models.BigIntegerField(default=0)
    topup_held_paise = models.PositiveBigIntegerField(default=0)
    credit_buckets_version = models.PositiveSmallIntegerField(default=0)
    @property
    def available_paise(self):
        return self.balance_paise - self.held_paise
    @property
    def monthly_available_paise(self):
        return self.monthly_balance_paise - self.monthly_held_paise
    @property
    def topup_available_paise(self):
        return self.topup_balance_paise - self.topup_held_paise
    @property
    def free_available_paise(self):
        return self.available_paise - self.monthly_available_paise - self.topup_available_paise

class Ledger(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    account = models.ForeignKey(Account, on_delete=models.PROTECT)
    kind = models.CharField(max_length=20)
    balance_delta = models.BigIntegerField(default=0)
    held_delta = models.BigIntegerField(default=0)
    balance_after = models.BigIntegerField()
    held_after = models.PositiveBigIntegerField()
    reference = models.CharField(max_length=160, unique=True)
    created_at = models.DateTimeField(auto_now_add=True, db_index=True)
    class Meta:
        indexes = [models.Index(fields=["account", "-created_at", "-id"])]

class Rate(models.Model):
    slug = models.SlugField(primary_key=True)
    title = models.CharField(max_length=100)
    kind = models.CharField(max_length=12, choices=[("image", "Image"), ("video", "Video"), ("voice", "Voice")])
    enabled = models.BooleanField(default=False)
    provider_model = models.CharField(max_length=120)
    config = models.JSONField(default=dict)
    # Unit = one image, one video second, or 1,000 voice characters (rounded up).
    # Legacy database names. These are raw provider USD-cent allowances; customer
    # charges are calculated in credits by services.price().
    unit_cost_paise = models.PositiveIntegerField()
    overhead_paise = models.PositiveIntegerField(default=0)
    max_units = models.PositiveIntegerField(default=1)
    revision = models.PositiveIntegerField(default=1)
    updated_at = models.DateTimeField(auto_now=True)


class ShowcaseTemplate(models.Model):
    """Owner-curated example media; customers supply their own input for generation."""
    MODE_CHOICES = [
        ("image_to_video", "Image to Video"),
        ("text_to_video", "Text to Video"),
        ("create_image", "Create Image"),
    ]
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    title = models.CharField(max_length=90)
    category = models.CharField(max_length=40)
    prompt = models.CharField(max_length=500)
    mode = models.CharField(max_length=20, choices=MODE_CHOICES)
    rate = models.ForeignKey(Rate, on_delete=models.PROTECT)
    aspect_ratio = models.CharField(max_length=10, default="9:16")
    duration = models.PositiveSmallIntegerField(default=5)
    poster_key = models.CharField(max_length=300, blank=True)
    video_key = models.CharField(max_length=300, blank=True)
    example_input_key = models.CharField(max_length=300, blank=True)
    featured = models.BooleanField(default=False)
    requires_reference = models.BooleanField(default=False, db_default=False)
    published = models.BooleanField(default=False)
    position = models.PositiveSmallIntegerField(default=0)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        indexes = [models.Index(fields=["published", "category", "position", "-created_at"], name="showcase_feed_idx")]

class ShowcaseBanner(models.Model):
    DESTINATIONS = [("create_image", "Create Image"), ("image_to_video", "Image to Video"), ("text_to_video", "Text to Video"), ("templates", "Templates")]
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    title = models.CharField(max_length=90)
    image_key = models.CharField(max_length=300, blank=True)
    destination = models.CharField(max_length=20, choices=DESTINATIONS, default="templates")
    position = models.PositiveSmallIntegerField(default=0)
    display_seconds = models.PositiveSmallIntegerField(default=4)
    published = models.BooleanField(default=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        ordering = ("position", "id")


class Asset(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    account = models.ForeignKey(Account, on_delete=models.PROTECT)
    key = models.CharField(max_length=300, unique=True)
    mime = models.CharField(max_length=64)
    size = models.PositiveBigIntegerField()
    ready = models.BooleanField(default=False)
    deleted = models.BooleanField(default=False)
    thumbnail_key = models.CharField(max_length=340, blank=True, default="", db_default="")
    thumbnail_size = models.PositiveIntegerField(default=0, db_default=0)
    thumbnail_state = models.CharField(max_length=12, default="none", db_default="none", choices=[(value, value) for value in ("none", "pending", "processing", "ready", "failed")])
    thumbnail_lease_until = models.DateTimeField(null=True)
    thumbnail_attempts = models.PositiveSmallIntegerField(default=0, db_default=0)
    created_at = models.DateTimeField(auto_now_add=True)
    class Meta:
        indexes = [models.Index(fields=["account", "-created_at"]),
                   models.Index(fields=["thumbnail_state", "created_at", "id"], name="asset_thumbnail_queue_idx",
                                condition=Q(thumbnail_state__in=("pending", "processing")))]

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
    provider_context = models.JSONField(default=dict)
    output = models.ForeignKey(Asset, on_delete=models.PROTECT, null=True)
    budget = models.ForeignKey(Budget, on_delete=models.PROTECT)
    actual_cost_paise = models.PositiveIntegerField(null=True)
    charged_paise = models.PositiveIntegerField(null=True)
    monthly_reserved_paise = models.PositiveIntegerField(default=0)
    topup_reserved_paise = models.PositiveIntegerField(default=0)
    monthly_period_key = models.CharField(max_length=160, blank=True)
    error_code = models.CharField(max_length=40, blank=True)
    next_attempt_at = models.DateTimeField(db_index=True)
    lease_until = models.DateTimeField(null=True)
    lease_id = models.UUIDField(null=True)
    attempts = models.PositiveIntegerField(default=0)
    settled_at = models.DateTimeField(null=True, db_index=True)
    cost_checked_at = models.DateTimeField(null=True, db_index=True)
    cost_record = models.ForeignKey("ProviderCost", on_delete=models.PROTECT, null=True, related_name="current_for_jobs")
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)
    class Meta:
        constraints = [models.UniqueConstraint(fields=["account", "idempotency_key"], name="job_account_idempotency"), models.CheckConstraint(condition=Q(state__in=("queued", "submitting", "running", "uncertain", "succeeded", "failed", "cancelled")), name="job_valid_state")]
        indexes = [models.Index(fields=["state", "next_attempt_at"]),
                   models.Index(fields=["account", "-created_at", "-id"]),
                   models.Index(fields=["account", "-created_at", "-id"], name="job_active_account_idx",
                                condition=Q(state__in=("queued", "submitting", "running", "uncertain"))),
                   models.Index(fields=["next_attempt_at", "lease_until"], name="job_claim_pending_idx",
                                condition=Q(state__in=("queued", "submitting", "running", "uncertain")))]

class Product(models.Model):
    sku = models.CharField(primary_key=True, max_length=150)
    title = models.CharField(max_length=100)
    # Legacy database name. This is the integer customer-credit grant.
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


class Subscription(models.Model):
    """A server-verified Google Play subscription; never trust the device alone."""
    PLAN_CHOICES = [("creator", "Creator"), ("legend", "Legend")]
    STATE_CHOICES = [("active", "Active"), ("grace", "Grace period"), ("cancelled", "Cancelled"),
                     ("on_hold", "On hold"), ("expired", "Expired"), ("revoked", "Revoked")]
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    account = models.ForeignKey(Account, on_delete=models.PROTECT)
    product_id = models.CharField(max_length=150)
    # The product ID is not globally unique. The encrypted purchase token is.
    token_hash = models.CharField(max_length=64, unique=True)
    token_encrypted = models.TextField()
    plan = models.CharField(max_length=16, choices=PLAN_CHOICES)
    state = models.CharField(max_length=16, choices=STATE_CHOICES, default="active")
    auto_renewing = models.BooleanField(default=False)
    expires_at = models.DateTimeField(null=True, db_index=True)
    latest_order_id = models.CharField(max_length=200, blank=True)
    last_grant_key = models.CharField(max_length=260, blank=True, null=True, unique=True, default=None)
    checked_at = models.DateTimeField(null=True, db_index=True)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)
    class Meta:
        indexes = [models.Index(fields=["account", "state", "expires_at"])]


class ProviderCost(models.Model):
    """Append-only revisions; never change customer charges when bills are reconciled."""
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    job = models.ForeignKey(Job, on_delete=models.PROTECT, related_name="cost_history")
    amount_paise = models.PositiveBigIntegerField()
    original_usd = models.DecimalField(max_digits=20, decimal_places=9, null=True)
    usd_inr = models.DecimalField(max_digits=14, decimal_places=6, null=True)
    source = models.CharField(max_length=16, choices=[("fal_billing", "fal billed USD"), ("invoice", "INR invoice")])
    evidence = models.CharField(max_length=250)
    fingerprint = models.CharField(max_length=64)
    recorded_by = models.ForeignKey("auth.User", on_delete=models.PROTECT, null=True)
    created_at = models.DateTimeField(auto_now_add=True)
    class Meta:
        indexes = [models.Index(fields=["job", "-created_at"])]


class CashEntry(models.Model):
    """Actual cash movements supplied from payment/bank statements, not wallet credits."""
    KINDS = [("receipt", "Customer payment (gross)"), ("refund", "Customer refund"), ("store_fee", "Store/payment fee"), ("expense", "Hosting, tax or other expense"), ("provider_topup", "Provider credit top-up")]
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    occurred_at = models.DateTimeField(db_index=True)
    kind = models.CharField(max_length=20, choices=KINDS)
    amount_paise = models.BigIntegerField()
    account = models.ForeignKey(Account, on_delete=models.PROTECT, null=True)
    purchase = models.ForeignKey(Purchase, on_delete=models.PROTECT, null=True)
    reference = models.CharField(max_length=160, unique=True)
    note = models.CharField(max_length=500)
    reversal_of = models.OneToOneField("self", on_delete=models.PROTECT, null=True, related_name="reversal")
    recorded_by = models.ForeignKey("auth.User", on_delete=models.PROTECT)
    created_at = models.DateTimeField(auto_now_add=True)
    class Meta:
        indexes = [models.Index(fields=["account", "occurred_at"])]


class OwnerAudit(models.Model):
    owner = models.ForeignKey("auth.User", on_delete=models.PROTECT)
    action = models.CharField(max_length=40)
    reference = models.CharField(max_length=250, blank=True)
    created_at = models.DateTimeField(auto_now_add=True, db_index=True)
