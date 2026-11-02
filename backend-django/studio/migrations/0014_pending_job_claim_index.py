from django.db import migrations, models
from django.db.models import Q


class Migration(migrations.Migration):
    dependencies = [("studio", "0013_replace_500_pack_id")]
    operations = [
        migrations.AddIndex(
            model_name="job",
            index=models.Index(
                fields=["next_attempt_at", "lease_until"], name="job_claim_pending_idx",
                condition=Q(state__in=("queued", "submitting", "running", "uncertain")),
            ),
        ),
    ]
