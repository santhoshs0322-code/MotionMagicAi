from rest_framework.exceptions import APIException
from rest_framework.response import Response
from django.db import OperationalError, IntegrityError

class Problem(APIException):
    def __init__(self, message, status=400):
        self.status_code = status
        super().__init__(message)

def exception_handler(exc, context):
    from rest_framework.views import exception_handler as drf_handler
    result = drf_handler(exc, context)
    if result is not None:
        result.data = {"ok": False, "message": str(exc.detail) if isinstance(exc.detail, str) else "Invalid request."}
        return result
    if isinstance(exc, (OperationalError, IntegrityError)):
        return Response({"ok": False, "message": "Temporarily busy. Retry using the same idempotency key."}, status=503)
    # Never return upstream bodies, tokens, or local configuration in API errors.
    return Response({"ok": False, "message": "Service temporarily unavailable."}, status=503)
