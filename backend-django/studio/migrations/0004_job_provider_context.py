from django.db import migrations, models


class Migration(migrations.Migration):
    dependencies = [("studio", "0003_rate_config")]
    operations = [migrations.AddField(model_name="job", name="provider_context", field=models.JSONField(default=dict))]
