"""Account-scoped testing feedback. No provider requests or credit grants."""
import uuid
import ipaddress

from django.conf import settings
from django.db import transaction
from rest_framework.response import Response

from . import authentication
from .errors import Problem
from .models import Job, Wallet, WebFeedback, WebVisitorInterest
from .webapp import BrowserPrivate
from .web_auth import BrowserPublic


class VisitorInterest(BrowserPublic):
    def post(self, request):
        from .web_access import visitor_key
        if not settings.WEB_TRIAL_MODE:
            raise Problem("Testing feedback is currently closed.", 404)
        peer = request.META.get("REMOTE_ADDR", "unknown")
        if settings.PROXY_MODE in ("alb", "render"):
            try:
                peer = str(ipaddress.ip_address(request.headers.get("X-Forwarded-For", "").split(",")[-1].strip()))
            except ValueError:
                raise Problem("Invalid proxy routing.", 400) from None
        authentication.rate_limit("web-visitor-interest-ip:"+peer, 60, seconds=300)
        key = visitor_key(request)
        if not key:
            raise Problem("Reload the page to start your anonymous feedback session.", 400)
        authentication.rate_limit("web-visitor-interest:"+key, 10, seconds=300)
        data = request.data
        if not isinstance(data, dict) or set(data)-{"outcome", "interest", "goal"}:
            raise Problem("Invalid visitor feedback fields.")
        outcome, interest, goal = data.get("outcome"), data.get("interest", ""), data.get("goal", "")
        if outcome not in ("submitted", "dismissed") or interest not in ("", "yes", "no", "maybe") or not isinstance(goal, str) or len(goal)>300:
            raise Problem("Invalid visitor feedback answer.")
        if outcome=="submitted" and not interest:
            raise Problem("Please choose an answer.")
        WebVisitorInterest.objects.get_or_create(visitor_hash=key, defaults={"outcome": outcome,
            "interest": interest if outcome=="submitted" else "", "goal": goal.strip() if outcome=="submitted" else ""})
        return Response({"ok": True, "recorded": True})


class Feedback(BrowserPrivate):
    def get(self, request):
        if not settings.WEB_TRIAL_MODE:
            return Response({"ok": True, "pending": [], "exhausted": False})
        from .views import job_rows_public
        pending = list(Job.objects.filter(account=request.user, webgeneration__testing=True,
            state="succeeded", webfeedback__isnull=True).select_related("quote", "output").order_by("created_at", "id")[:10])
        wallet = Wallet.objects.get(account=request.user)
        from .web_testing import allowance
        trial = allowance(request.user)
        exhausted = (wallet.balance_paise<=0 or trial["spent"]>=trial["limit"]) and wallet.held_paise==0 and trial["held"]==0
        last = Job.objects.filter(account=request.user, state="succeeded", webgeneration__testing=True).order_by("-created_at", "-id").values_list("pk", flat=True).first() if exhausted else None
        answered = WebFeedback.objects.filter(account=request.user, context="credits").exists() or bool(last and WebFeedback.objects.filter(account=request.user, job_id=last).exists())
        return Response({"ok": True, "pending": job_rows_public(pending), "exhausted": exhausted,
            "credits_answered": answered})

    def post(self, request):
        if not settings.WEB_TRIAL_MODE:
            raise Problem("Testing feedback is currently closed.", 404)
        authentication.rate_limit("web-feedback:"+str(request.user.pk), 30)
        data = request.data
        if not isinstance(data, dict) or set(data)-{"context", "job_id", "outcome", "rating", "interest", "comment"}:
            raise Problem("Invalid feedback fields.")
        context, outcome = data.get("context"), data.get("outcome")
        rating, interest, comment = data.get("rating", ""), data.get("interest", ""), data.get("comment", "")
        if context not in ("generation", "credits") or outcome not in ("submitted", "dismissed"):
            raise Problem("Choose a feedback context and outcome.")
        if rating not in ("", "like", "dislike") or interest not in ("", "yes", "no", "maybe") or not isinstance(comment, str) or len(comment)>500:
            raise Problem("Invalid feedback answer or comment length.")
        if outcome=="submitted" and (not interest or context=="generation" and not rating):
            raise Problem("Please answer the feedback questions.")
        job = None
        if context=="generation":
            try:
                identifier = uuid.UUID(str(data.get("job_id", "")))
            except ValueError:
                raise Problem("Creation not found.", 404) from None
            job = Job.objects.filter(pk=identifier, account=request.user, state="succeeded", webgeneration__testing=True).first()
            if not job:
                raise Problem("Creation not found.", 404)
        elif data.get("job_id"):
            raise Problem("Credit feedback must not include a creation.")
        with transaction.atomic():
            feedback, _ = WebFeedback.objects.get_or_create(account=request.user, context=context, job=job,
                defaults={"outcome": outcome, "rating": rating if outcome=="submitted" else "",
                    "interest": interest if outcome=="submitted" else "", "comment": comment.strip() if outcome=="submitted" else ""})
        return Response({"ok": True, "recorded": True, "outcome": feedback.outcome})
