"""Retired testing endpoints: keep old clients from writing new trial responses."""
from rest_framework.response import Response
from .web_auth import BrowserPublic
from .webapp import BrowserPrivate


class Feedback(BrowserPrivate):
    def get(self, request):
        return Response({"ok": True, "pending": [], "exhausted": False, "credits_answered": True})

    def post(self, request):
        return Response({"ok": False, "message": "Web testing has ended. Reload your studio."}, status=410)


class VisitorInterest(BrowserPublic):
    def post(self, request):
        return Response({"ok": False, "message": "The visitor survey has ended. Continue with Google sign-in."}, status=410)
