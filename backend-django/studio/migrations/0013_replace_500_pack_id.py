from django.db import migrations


def replace_500_pack(apps, schema_editor):
    Product = apps.get_model("studio", "Product")
    Product.objects.filter(sku="motion_topup_500").update(enabled=False)
    Product.objects.update_or_create(
        sku="motion_credits_500_k7p4",
        defaults={"title": "500 permanent credits", "credit_paise": 500, "enabled": False},
    )


class Migration(migrations.Migration):
    dependencies = [("studio", "0012_pack_10000")]
    operations = [migrations.RunPython(replace_500_pack, migrations.RunPython.noop)]
