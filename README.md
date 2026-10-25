# Healthbridge

Your Android Health Connect data on your own server, with a REST API and an MCP server so scripts
and AI assistants can use it.

A hard fork of [HCGateway](https://github.com/ShuchirJ/HCGateway) by Shuchir Jain (MIT), with a
rewritten server and a new native Android app.

```
Samsung Health / Strava / watch ─▶ Health Connect ─▶ Healthbridge app ─▶ your server ─▶ API · MCP
```

## Features

- **Native Android app** (Kotlin, Compose). Uses the Health Connect *changes API*: the first run
  imports the last N days, later runs only send what is new, edited or deleted. Background sync via
  WorkManager. Errors are shown on the Activity tab instead of being skipped silently, and the
  Overview tab checks the things that break background sync in practice (background-read permission,
  history permission, battery optimisation).
- **Workouts that make sense.** Health Connect stores heart rate, distance and calories as separate
  records. `GET /api/v2/workouts` joins them to the matching exercise session, without double
  counting data several apps report for the same activity.
- **MCP server** at `/mcp` for AI assistants (read-only tools).
- **Read-only API keys** for scripts and agents, so they never touch the phone's login session.
- Production server (gunicorn), non-root container, no payload logging, login rate limiting,
  registration that closes after the first account.

## Self-host

One line (Docker with the compose plugin):

```sh
curl -fsSL https://raw.githubusercontent.com/mteij/healthbridge/main/docker-compose.yml | docker compose -p healthbridge -f - up -d
```

This starts the server on port `6644` and a MongoDB that is not exposed to the network. Then:

1. Put a reverse proxy with HTTPS in front of it (Traefik, Caddy, nginx…). The phone app refuses plain `http://`.
2. Open the app and sign in with your server address and a **new** username and password. The first
   account becomes the owner and registration closes. Do this straight away.
3. Open the server's address in a browser: the landing page repeats these steps for your host.

Settings (environment variables on the `api` service):

| Variable | Purpose | Default |
|---|---|---|
| `MONGO_URI` | MongoDB connection string | set by the compose file |
| `HB_DATA_KEY` | Secret used to encrypt stored records. Set it before the first sync | unset (legacy key) |
| `HB_ALLOW_REGISTRATION` | `true` to allow more than one account | closed after the first account |
| `HB_TIMEZONE` | Timezone for daily totals and sleep dates in the MCP tools | `Europe/Amsterdam` |
| `HB_LOG_LEVEL` | Log level | `INFO` |
| `FCM_PROJECT_ID` | Only for the optional push/delete-to-device endpoints (needs a Firebase `service-account.json`) | unset |

Back up the `db` volume. Without `HB_DATA_KEY` the stored records use upstream's legacy
encryption, which is weak (all users share one key); `scripts/reencrypt.py` converts existing data
after you set the key.

## Android app

Download the APK from the [latest release](../../releases/latest), or let
[Obtainium](https://github.com/ImranR98/Obtainium) keep it updated:
[**add to Obtainium**](obtainium://add/https://github.com/mteij/healthbridge)
(or add `https://github.com/mteij/healthbridge` as a source manually).

Needs Android 9+ and Health Connect (built in from Android 14, otherwise install it from Google
Play). On first start follow the checklist on the Overview tab: allow reading, allow background
reading and history, and exclude the app from battery optimisation (Samsung and other vendors
otherwise put it to sleep after a few days).

## API keys

Keys are read-only and shown once. Log in, then create one:

```sh
curl -s -X POST https://your.server/api/v2/login \
  -H 'Content-Type: application/json' -d '{"username":"you","password":"…"}'

curl -s -X POST https://your.server/api/v2/apikeys \
  -H "Authorization: Bearer <token from the login response>" \
  -H 'Content-Type: application/json' -d '{"name":"my-agent"}'
```

Use the returned `hb_…` key as `Authorization: Bearer hb_…`. `GET /api/v2/apikeys` lists keys and
`DELETE /api/v2/apikeys/<id>` revokes one.

## MCP (AI assistants)

Endpoint: `https://your.server/mcp` (streamable HTTP, stateless, Bearer API key).

Claude Code:

```sh
claude mcp add --transport http healthbridge https://your.server/mcp \
  --header "Authorization: Bearer hb_…"
```

Any other client that supports remote HTTP MCP servers takes the same URL and header.

| Tool | What it returns |
|---|---|
| `get_overview` | Latest measurements, last 7 days of steps and calories, recent sleep and workouts |
| `list_workouts` | Workouts with distance, duration, pace, heart rate, calories (`from`, `to`, `type`, `limit`) |
| `get_workout` | One workout with heart-rate and speed samples |
| `get_daily_totals` | Per-day totals for steps, distance, calories, floors, elevation |
| `get_sleep` | Sleep sessions with minutes asleep and per stage |
| `get_latest_measurements` | Weight, body fat, resting heart rate, VO2 max, HRV, SpO2, height |

All tools only read. Treat the key like a password: it can see all of your health data.

## REST API

`/api/v2`, authenticated with the app's login session or an API key (read-only keys: `fetch` and `workouts` only).

| Endpoint | |
|---|---|
| `POST /login`, `POST /refresh`, `DELETE /revoke` | Session handling. Logging in never rotates the phone's refresh token |
| `POST /sync/<type>`, `DELETE /sync/<type>` | Upload or delete records (the app uses these) |
| `POST /fetch/<type>` | Raw records, optional `{"queries": {…}, "limit": n}` |
| `GET /workouts?from=&to=&type=&limit=&samples=1` | Workouts with joined data |
| `GET /workouts/<id>` | One workout with sample series |
| `POST/GET/DELETE /apikeys` | Manage read-only keys |
| `PUT /push/<type>`, `DELETE /delete/<type>` | Ask the phone to write or delete records (needs FCM) |
| `GET /healthz` | Health check (no auth) |

Record types are the lowerCamelCase Health Connect names (`steps`, `heartRate`, `exerciseSession`,
`sleepSession`, `vo2Max`, …). The routes of upstream's v2 API remain compatible.

## Development

```sh
api/run-tests.sh                 # server tests against a throwaway MongoDB (Docker)
docker build -t healthbridge-api api
```

The app lives in `app/` (Gradle, Kotlin 2.4, Compose, Health Connect `connect-client` 1.1). CI
(`.github/workflows/android.yml`) builds and signs the APK and attaches it to every `v*` release;
`.github/workflows/docker.yml` publishes `ghcr.io/mteij/healthbridge`. To sign with your own key
set the repository secrets `HB_KEYSTORE_B64`, `HB_KEYSTORE_PASSWORD` and `HB_KEY_ALIAS`.

Layout: `api/` server · `app/` Android app · `scripts/` maintenance scripts · `doc/` upstream API reference.

## Licence

MIT, as upstream. See `LICENSE`.
