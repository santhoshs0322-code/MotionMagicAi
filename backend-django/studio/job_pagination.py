"""Bounded, account-bound keyset pagination for generation history."""
import uuid

from django.core import signing
from django.db.models import Q
from django.utils import timezone
from django.utils.dateparse import parse_datetime

from .errors import Problem


PAGE_SIZE = 30
SALT = "motion-ai.job-history.v1"


def job_page(request, queryset, scope):
    cursor = request.query_params.get("cursor", "")
    if cursor:
        try:
            if len(cursor) > 1024:
                raise ValueError()
            account, saved_scope, created_at, identifier = signing.loads(cursor, salt=SALT)
            created_at = parse_datetime(created_at)
            identifier = uuid.UUID(identifier)
            if account != str(request.user.pk) or saved_scope != scope or created_at is None or timezone.is_naive(created_at):
                raise ValueError()
        except (signing.BadSignature, ValueError, TypeError):
            raise Problem("Invalid project page. Refresh projects and retry.") from None
        queryset = queryset.filter(Q(created_at__lt=created_at) | Q(created_at=created_at, pk__lt=identifier))

    # The (account, -created_at, -id) index avoids counting or skipping older rows.
    rows = list(queryset.order_by("-created_at", "-pk")[:PAGE_SIZE + 1])
    more = len(rows) > PAGE_SIZE
    rows = rows[:PAGE_SIZE]
    if not more:
        return rows, None, None
    last = rows[-1]
    next_cursor = signing.dumps([str(request.user.pk), scope, last.created_at.isoformat(), str(last.pk)], salt=SALT)
    params = request.query_params.copy()
    params.pop("tracked", None)
    params["cursor"] = next_cursor
    return rows, next_cursor, request.build_absolute_uri(request.path + "?" + params.urlencode())
