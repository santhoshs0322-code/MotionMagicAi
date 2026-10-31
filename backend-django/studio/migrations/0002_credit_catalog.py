from django.db import migrations


def add_catalog(apps, schema_editor):
    Product = apps.get_model("studio", "Product")
    # These are deliberately disabled until the matching one-time Play products
    # exist and server-side Play verification has been enabled. They are credit
    # packs, not subscriptions; never grant recurring credits from this table.
    Product.objects.update_or_create(
        sku="motion_creator_credits",
        defaults={"title": "Creator — 600 credits", "credit_paise": 600, "enabled": False},
    )
    Product.objects.update_or_create(
        sku="motion_legend_credits",
        defaults={"title": "Legend — 1,200 credits", "credit_paise": 1200, "enabled": False},
    )


class Migration(migrations.Migration):
    dependencies = [("studio", "0001_initial")]
    operations = [migrations.RunPython(add_catalog, migrations.RunPython.noop)]
