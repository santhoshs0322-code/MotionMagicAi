import uuid
from django.db import migrations, models


class Migration(migrations.Migration):
    dependencies = [("studio", "0018_showcase_requires_reference")]
    operations = [migrations.CreateModel(name="ShowcaseBanner", fields=[
        ("id", models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False, serialize=False)),
        ("title", models.CharField(max_length=90)),
        ("image_key", models.CharField(max_length=300, blank=True)),
        ("destination", models.CharField(max_length=20, choices=[("create_image", "Create Image"), ("image_to_video", "Image to Video"), ("text_to_video", "Text to Video"), ("templates", "Templates")], default="templates")),
        ("position", models.PositiveSmallIntegerField(default=0)),
        ("display_seconds", models.PositiveSmallIntegerField(default=4)),
        ("published", models.BooleanField(default=True)),
        ("updated_at", models.DateTimeField(auto_now=True)),
    ], options={"ordering": ("position", "id")})]
