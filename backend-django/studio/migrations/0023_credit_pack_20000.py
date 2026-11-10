from django.db import migrations


def update_credit_packs(apps, schema_editor):
    Product = apps.get_model("studio", "Product")
    products = Product.objects.using(schema_editor.connection.alias)
    products.filter(pk__in=("motion_topup_500", "motion_credits_500_k7p4")).update(enabled=False)
    products.update_or_create(sku="motion_pack_20000",
        defaults={"title": "20,000 permanent credits", "credit_paise": 20000, "enabled": False})


class Migration(migrations.Migration):
    dependencies = [("studio", "0022_story_work_slots")]
    operations = [migrations.RunPython(update_credit_packs, migrations.RunPython.noop)]
