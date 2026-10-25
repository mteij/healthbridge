# Healthbridge

A self-hosted gateway between Android's Health Connect and your own server.
Hard fork of [HCGateway](https://github.com/ShuchirJ/HCGateway) by Shuchir Jain (MIT).

- `api/` — server (Flask + MongoDB, served by gunicorn)
- `app/` — Android app (being rewritten in Kotlin)
- `scripts/` — maintenance scripts
- `doc/api-documentation.yml` — upstream API reference (v2 routes are still compatible)

## What differs from upstream

- Production server (gunicorn), health check at `/healthz`, runs as non-root.
- No payload logging; the login no longer overwrites the phone's refresh token.
- Registration is closed once the first user exists (`HB_ALLOW_REGISTRATION=true` to reopen).
- Failed logins are rate limited per username.
- **Read-only API keys** (`hb_…`) for scripts and agents: `POST /api/v2/apikeys` with a login
  session, then use `Authorization: Bearer hb_…` on `/fetch/*` and `/workouts`.
- **`GET /api/v2/workouts`** joins exercise sessions with heart rate, distance, speed, energy
  and steps into one workout (`?from=&to=&type=running&limit=&samples=1`);
  `GET /api/v2/workouts/<id>` returns one with sample series. Overlapping data from several
  apps is not double counted: the workout's own source wins.
- Timestamps are stored in one fixed UTC format so range queries are reliable.
- Optional `HB_DATA_KEY` for record encryption (see `scripts/reencrypt.py`).

## Configuration (`api/.env`)

| Variable | Purpose |
|---|---|
| `MONGO_URI` | MongoDB connection string |
| `FCM_PROJECT_ID` | Firebase project, only needed for push/delete to the device |
| `HB_DATA_KEY` | optional secret for record encryption |
| `HB_ALLOW_REGISTRATION` | `true` to allow new accounts |
| `HB_LOG_LEVEL` | default `INFO` |

## Tests

`api/run-tests.sh` runs the suite against a throwaway MongoDB in Docker.
