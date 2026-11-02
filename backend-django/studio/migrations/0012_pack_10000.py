from django.db import migrations


def seed_pack(apps, schema_editor):
    Product = apps.get_model("studio", "Product")
    Product.objects.update_or_create(
        sku="motion_pack_10000",
        defaults={"title": "10,000 permanent credits", "credit_paise": 10000, "enabled": False},
    )


class Migration(migrations.Migration):
    dependencies = [("studio", "0011_story_credit_packs")]
    operations = [migrations.RunPython(seed_pack, migrations.RunPython.noop)]
