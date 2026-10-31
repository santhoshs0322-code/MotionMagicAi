from django.db import migrations, models
import django.db.models.deletion


class Migration(migrations.Migration):
    dependencies = [("studio", "0008_subscriptions_and_topups")]

    operations = [
        migrations.CreateModel(
            name="RefreshSession",
            fields=[
                ("token_hash", models.CharField(max_length=64, primary_key=True, serialize=False)),
                ("token_version", models.PositiveIntegerField()),
                ("expires_at", models.DateTimeField(db_index=True)),
                ("revoked_at", models.DateTimeField(null=True)),
                ("created_at", models.DateTimeField(auto_now_add=True)),
                ("account", models.ForeignKey(on_delete=django.db.models.deletion.PROTECT, to="studio.account")),
            ],
        ),
    ]
