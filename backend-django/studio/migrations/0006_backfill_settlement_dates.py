from itertools import islice
from django.db import migrations


def backfill(apps, schema_editor):
    Job = apps.get_model('studio', 'Job')
    Ledger = apps.get_model('studio', 'Ledger')
    alias = schema_editor.connection.alias
    rows = Job.objects.using(alias).filter(state__in=['succeeded', 'failed', 'cancelled'], settled_at__isnull=True).iterator(chunk_size=500)
    while batch := list(islice(rows, 500)):
        references = {f'job:{job.pk}:settle': job for job in batch}
        dates = dict(Ledger.objects.using(alias).filter(reference__in=references).values_list('reference', 'created_at'))
        for reference, job in references.items():
            job.settled_at = dates.get(reference, job.updated_at)
        Job.objects.using(alias).bulk_update(batch, ['settled_at'], batch_size=500)


class Migration(migrations.Migration):
    dependencies = [('studio', '0005_job_cost_checked_at_job_settled_at_owneraudit_and_more')]
    operations = [migrations.RunPython(backfill, migrations.RunPython.noop)]
