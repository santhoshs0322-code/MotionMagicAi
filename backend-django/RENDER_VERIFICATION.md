# Render deployment preparation — 29 October 2026

Prepared the root `render.yaml` for branch `dev`: Docker API, paid PostgreSQL 17 and free Redis-compatible cache in Singapore, private datastore connections, generated independent secrets, pre-deploy migrations and `/ready` health checks. The web process uses Render's port through a portable Gunicorn config. Explicit Render proxy mode adds only the platform's exact hostname and HTTPS origin; legacy AWS ALB configuration still works. Django CSRF middleware and secure cookie defaults were added during deployment checks; native JWT API requests remain usable with CSRF checks enabled.

Validation completed locally:

- All **17 Django tests passed**: the 9 existing authentication/financial/billing/input tests and 8 deployment/proxy/readiness tests. Google and Play requests were mocked; no external identity or purchase was created.
- `makemigrations --check --dry-run` reported no changes.
- `check --deploy --fail-level WARNING` reported no issues with representative Render production settings and 64-byte random secret inputs. PostgreSQL/cache addresses were placeholders; this command did not connect to them.
- `render.yaml` passed JSON Schema validation against the official schema downloaded from `https://render.com/schema/render.yaml.json` on the verification date.
- Gunicorn configuration inspection confirmed port 10000 and the initial one-worker/four-thread settings. No generation worker or paid provider is enabled in the Blueprint.

Tests used SQLite and the local-memory cache. Live PostgreSQL locking, Redis networking, real Google login, Docker image startup and Render runtime HTTPS routing still require a deployed integration check. Docker was unavailable on this workstation; no image build or live Render deployment is claimed. Render's generated 44-character Django secret may trigger Django's deploy-only W009 length heuristic; the setup guide explains replacing it with a longer random value when running that check.

This is an initial staging configuration. One-million-concurrent-user capacity has not been demonstrated. No cloud resource was created, no provider credentials were purchased, and no new secret value was committed. The AWS architecture remains a later migration target.

## External PostgreSQL update — 29 October 2026

The user selected Aiven PostgreSQL. The current Blueprint now accepts `MOTION_AI_DATABASE_URL` with `sync: false`, omits creation of a Render PostgreSQL resource and sets one API worker/two threads for the supplied 15-connection limit. Existing database resources/data are not deleted or migrated by this repository change. The setup guide covers manual entry for an already-created service and preserves the requirement for a separate Redis-compatible cache.

All 17 Django tests passed again; the Render settings test now confirms that an external PostgreSQL URI retains its `sslmode=require` option and that connections close after each request. The revised YAML passed the official Render JSON Schema. Tests used a placeholder database URI: no connection to the user's Aiven service was attempted, and no user password or actual database URI was written to the repository.
