from django.db import migrations, models


def queue_existing_results(apps, schema_editor):
    Asset = apps.get_model("studio", "Asset")
    Job = apps.get_model("studio", "Job")
    Asset.objects.filter(ready=True, deleted=False, pk__in=Job.objects.filter(state="succeeded").values("output_id")).update(thumbnail_state="pending")


class Migration(migrations.Migration):
    dependencies = [("studio", "0016_job_job_active_account_idx")]
    operations = [
        migrations.AddField(model_name="asset", name="thumbnail_key", field=models.CharField(blank=True, default="", db_default="", max_length=340)),
        migrations.AddField(model_name="asset", name="thumbnail_size", field=models.PositiveIntegerField(default=0, db_default=0)),
        migrations.AddField(model_name="asset", name="thumbnail_state", field=models.CharField(default="none", db_default="none", max_length=12, choices=[(value, value) for value in ("none", "pending", "processing", "ready", "failed")])),
        migrations.AddField(model_name="asset", name="thumbnail_lease_until", field=models.DateTimeField(null=True)),
        migrations.AddField(model_name="asset", name="thumbnail_attempts", field=models.PositiveSmallIntegerField(default=0, db_default=0)),
        migrations.AddIndex(model_name="asset", index=models.Index(fields=["thumbnail_state", "created_at", "id"], name="asset_thumbnail_queue_idx", condition=models.Q(thumbnail_state__in=("pending", "processing")))),
        migrations.RunPython(queue_existing_results, migrations.RunPython.noop),
    ]
