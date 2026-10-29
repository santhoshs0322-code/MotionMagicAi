from django.db import migrations, models


class Migration(migrations.Migration):
    dependencies = [("studio", "0002_budget_budget_valid_shard_job_job_valid_state_and_more")]
    operations = [
        migrations.AddField(
            model_name="rate",
            name="config",
            field=models.JSONField(default=dict),
        ),
    ]
