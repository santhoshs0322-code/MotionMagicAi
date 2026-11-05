from django.db import migrations, models


class Migration(migrations.Migration):
    dependencies = [("studio", "0017_asset_thumbnails")]
    operations = [migrations.AddField(model_name="showcasetemplate", name="requires_reference", field=models.BooleanField(default=False, db_default=False))]
