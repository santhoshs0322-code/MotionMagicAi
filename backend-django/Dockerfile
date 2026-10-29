FROM python:3.12-slim AS runtime
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PIP_DISABLE_PIP_VERSION_CHECK=1
RUN addgroup --system motion && adduser --system --ingroup motion motion
WORKDIR /app
COPY requirements.lock ./
RUN pip install --no-cache-dir -r requirements.lock
COPY --chown=motion:motion . .
USER motion
EXPOSE 8000
CMD ["gunicorn", "--config", "config/gunicorn.conf.py", "config.wsgi:application"]
