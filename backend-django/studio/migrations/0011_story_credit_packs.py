from django.db import migrations


def seed_credit_packs(apps, schema_editor):
    Product = apps.get_model("studio", "Product")
    for sku, credits in (("motion_pack_2500", 2500), ("motion_pack_5000", 5000)):
        Product.objects.update_or_create(
            sku=sku,
            defaults={"title": f"{credits:,} permanent credits", "credit_paise": credits, "enabled": False},
        )


class Migration(migrations.Migration):
    dependencies = [("studio", "0010_credit_buckets")]
    operations = [migrations.RunPython(seed_credit_packs, migrations.RunPython.noop)]
