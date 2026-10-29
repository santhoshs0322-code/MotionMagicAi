"""Portable API startup for Render now and ECS later; migrations run separately."""
import os

bind = "0.0.0.0:" + os.environ.get("PORT", "8000")
workers = int(os.environ.get("MOTION_AI_WEB_WORKERS", "2"))
threads = int(os.environ.get("MOTION_AI_WEB_THREADS", "4"))
timeout = 30
graceful_timeout = 30
max_requests = 5000
max_requests_jitter = 500
accesslog = "-"
errorlog = "-"
# Log method/path/status/timing without query strings, bodies or authorization headers.
access_log_format = '%(h)s %(t)s "%(m)s %(U)s %(H)s" %(s)s %(b)s %(L)s'
