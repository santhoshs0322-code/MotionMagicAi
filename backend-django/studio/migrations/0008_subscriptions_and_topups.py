import uuid
from django.db import migrations, models


def seed_play_products(apps, schema_editor):
    Product = apps.get_model("studio", "Product")
    # The old records were draft one-time packs. Keep them disabled for audit;
    # only the consumable top-up can be verified by the one-time endpoint.
    Product.objects.filter(sku__in=("motion_creator_credits", "motion_legend_credits")).update(enabled=False)
    Product.objects.update_or_create(
        sku="motion_topup_500",
        defaults={"title": "500 permanent credits", "credit_paise": 500, "enabled": False},
    )


class Migration(migrations.Migration):
    dependencies = [("studio", "0002_credit_catalog")]

    operations = [
        migrations.CreateModel(
            name="Subscription",
            fields=[
                ("id", models.UUIDField(default=uuid.uuid4, editable=False, primary_key=True, serialize=False)),
                ("product_id", models.CharField(max_length=150)),
                ("token_hash", models.CharField(max_length=64, unique=True)),
                ("token_encrypted", models.TextField()),
                ("plan", models.CharField(choices=[("creator", "Creator"), ("legend", "Legend")], max_length=16)),
                ("state", models.CharField(choices=[("active", "Active"), ("grace", "Grace period"), ("cancelled", "Cancelled"), ("on_hold", "On hold"), ("expired", "Expired"), ("revoked", "Revoked")], default="active", max_length=16)),
                ("auto_renewing", models.BooleanField(default=False)),
                ("expires_at", models.DateTimeField(db_index=True, null=True)),
                ("latest_order_id", models.CharField(blank=True, max_length=200)),
                ("last_grant_key", models.CharField(blank=True, max_length=260, null=True, unique=True)),
                ("checked_at", models.DateTimeField(db_index=True, null=True)),
                ("created_at", models.DateTimeField(auto_now_add=True)),
                ("updated_at", models.DateTimeField(auto_now=True)),
                ("account", models.ForeignKey(on_delete=models.deletion.PROTECT, to="studio.account")),
            ],
        ),
        migrations.AddIndex(model_name="subscription", index=models.Index(fields=["account", "state", "expires_at"], name="studio_subs_account_3ae3bb_idx")),
        migrations.RunPython(seed_play_products, migrations.RunPython.noop),
    ]
