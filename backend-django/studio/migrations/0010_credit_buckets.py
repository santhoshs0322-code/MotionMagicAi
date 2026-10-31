from django.db import migrations, models


class Migration(migrations.Migration):
    dependencies = [("studio", "0009_refresh_sessions")]

    operations = [
        migrations.AddField(model_name="wallet", name="monthly_balance_paise", field=models.PositiveBigIntegerField(default=0)),
        migrations.AddField(model_name="wallet", name="monthly_held_paise", field=models.PositiveBigIntegerField(default=0)),
        migrations.AddField(model_name="wallet", name="monthly_period_key", field=models.CharField(blank=True, max_length=160)),
        migrations.AddField(model_name="wallet", name="monthly_expires_at", field=models.DateTimeField(null=True)),
        migrations.AddField(model_name="wallet", name="topup_balance_paise", field=models.BigIntegerField(default=0)),
        migrations.AddField(model_name="wallet", name="topup_held_paise", field=models.PositiveBigIntegerField(default=0)),
        migrations.AddField(model_name="wallet", name="credit_buckets_version", field=models.PositiveSmallIntegerField(default=0)),
        migrations.AddField(model_name="job", name="monthly_reserved_paise", field=models.PositiveIntegerField(default=0)),
        migrations.AddField(model_name="job", name="topup_reserved_paise", field=models.PositiveIntegerField(default=0)),
        migrations.AddField(model_name="job", name="monthly_period_key", field=models.CharField(blank=True, max_length=160)),
        migrations.AlterField(model_name="subscription", name="last_grant_key", field=models.CharField(blank=True, default=None, max_length=260, null=True, unique=True)),
    ]
