<div align="center">

<img src="docs/logo.svg" width="96" alt="Healthbridge logo">

# Healthbridge

**Your Android Health Connect data on your own server — with an API and an MCP server for your scripts and AI assistants.**

[![Release](https://img.shields.io/github/v/release/mteij/healthbridge?style=flat-square&color=0b6e8a)](../../releases/latest)
[![Build](https://img.shields.io/github/actions/workflow/status/mteij/healthbridge/android.yml?style=flat-square&label=build)](../../actions)
[![Docker](https://img.shields.io/badge/ghcr.io-mteij%2Fhealthbridge-0b6e8a?style=flat-square&logo=docker&logoColor=white)](../../pkgs/container/healthbridge)
[![License](https://img.shields.io/github/license/mteij/healthbridge?style=flat-square)](LICENSE)

[**Public instance**](https://healthbridge.mteij.nl) · [**Download the app**](../../releases/latest) · [**Add to Obtainium**](obtainium://add/https://github.com/mteij/healthbridge) · [**Self-host**](#self-host)

</div>

## Why

Health Connect keeps a workout, its heart rate, distance and calories as separate records. Healthbridge
syncs them off your phone reliably and puts them back together:

- 🔄 **Reliable sync.** Uses Health Connect's changes API: one import, then only what is new, edited or deleted. Failures show up in the app instead of vanishing.
- 🏃 **Real workouts.** `GET /api/v2/workouts` joins sessions with heart rate, distance, speed and energy, without double counting apps that report the same activity.
- 🤖 **AI-ready.** A built-in [MCP server](#mcp) lets assistants like Claude read your data (read-only).
- 🔑 **Safe by default.** Read-only API keys, rate-limited logins, no payload logging, non-root container.

## Quick start

| | |
|---|---|
| **1. Get the app** | [Download the APK](../../releases/latest) or [add it to Obtainium](obtainium://add/https://github.com/mteij/healthbridge) for automatic updates. Android 9+ with Health Connect. |
| **2. Pick a server** | Use the [public instance](#public-instance) or [self-host](#self-host). |
| **3. Sign in** | Enter the server address and a username and password (the first sign-in creates the account), then follow the checklist on the Overview tab. |

### Public instance

**https://healthbridge.mteij.nl** is a free instance run by the maintainer. **Best effort: no uptime, retention or support promises.**

- 🎟️ **Sign-ups are only open to the first 500 users.** When the spots are gone, [self-host](#self-host).
- 🧹 **Accounts that have been inactive for 30 days are deleted**, together with all their data.
- 🔒 Your records are stored on the maintainer's server and could technically be read by them, so for anything sensitive, self-host.

## Self-host

```sh
curl -fsSL https://raw.githubusercontent.com/mteij/healthbridge/main/docker-compose.yml | docker compose -p healthbridge -f - up -d
```

Starts the server on port `6644` and a MongoDB that is not reachable from outside. Put an HTTPS reverse proxy in front of it
(the app refuses plain `http://`), then sign in from the app. **The first account becomes the owner and registration closes**, so do this right away.

<details>
<summary><b>Configuration</b></summary>

Copy [`.env.example`](.env.example) to `.env` next to `docker-compose.yml` (clone the repo, or download both files) and edit what you need:

```sh
cp .env.example .env && docker compose up -d
```

| Variable | Purpose | Default |
|---|---|---|
| `HB_DATA_KEY` | Secret that encrypts stored records. Set it **before** the first sync (`openssl rand -base64 48`) | empty (weak legacy key) |
| `HB_ALLOW_REGISTRATION` | `true` to let more people create accounts | closed after the first account |
| `HB_MAX_USERS` | Cap on accounts while registration is open | unlimited |
| `HB_STALE_DAYS` | Delete accounts, and all their data, inactive for this many days. Any sync, login or API key use counts as activity | disabled |
| `HB_STALE_EXEMPT` | Comma separated usernames that are never deleted | none |
| `HB_STALE_DRY_RUN` | `true` to only log what would be deleted | `false` |
| `HB_TIMEZONE` | Timezone for daily totals and sleep dates in the MCP tools | `Europe/Amsterdam` |
| `HB_LOG_LEVEL` | Log level | `INFO` |
| `FCM_PROJECT_ID` | Only for the optional push/delete-to-device endpoints (needs a Firebase `service-account.json`) | empty |

Back up the `db` volume. Data written with the old encryption can be converted with `scripts/reencrypt.py` after you set `HB_DATA_KEY`.
</details>

## Use your data

Create a read-only API key (shown once). Log in, then:

```sh
curl -s -X POST https://your.server/api/v2/login -H 'Content-Type: application/json' \
  -d '{"username":"you","password":"…"}'

curl -s -X POST https://your.server/api/v2/apikeys -H "Authorization: Bearer <token>" \
  -H 'Content-Type: application/json' -d '{"name":"my-agent"}'
```

Send it as `Authorization: Bearer hb_…`. Try `GET /api/v2/workouts?limit=5`.

### MCP

Endpoint `https://your.server/mcp` (streamable HTTP, stateless, Bearer key). With Claude Code:

```sh
claude mcp add --transport http healthbridge https://your.server/mcp --header "Authorization: Bearer hb_…"
```

| Tool | Returns |
|---|---|
| `get_overview` | Latest measurements, 7 days of steps and calories, recent sleep and workouts |
| `list_workouts` · `get_workout` | Workouts with distance, pace, heart rate, calories; one workout with samples |
| `get_daily_totals` | Per-day steps, distance, calories, floors, elevation |
| `get_sleep` | Sleep sessions with minutes asleep and per stage |
| `get_latest_measurements` | Weight, body fat, resting heart rate, VO₂ max, HRV, SpO₂, height |

All tools are read-only. Treat a key like a password: it can see all of your health data.

<details>
<summary><b>REST API</b></summary>

Under `/api/v2`. Authenticate with the app's login session or an API key (keys are read-only: `fetch` and `workouts`).

| Endpoint | |
|---|---|
| `POST /login` · `/refresh` · `DELETE /revoke` | Sessions. Logging in never rotates the phone's refresh token |
| `POST /sync/<type>` · `DELETE /sync/<type>` | Upload or delete records (used by the app) |
| `POST /fetch/<type>` | Raw records, optional `{"queries": {…}, "limit": n}` |
| `GET /workouts?from=&to=&type=&limit=&samples=1` | Workouts with joined data |
| `GET /workouts/<id>` | One workout with sample series |
| `POST` · `GET` · `DELETE /apikeys` | Manage read-only keys |
| `PUT /push/<type>` · `DELETE /delete/<type>` | Ask the phone to write or delete records (needs FCM) |
| `GET /healthz` · `GET /status` | Health check and public status (version, open sign-ups, spots left), no auth |

Record types are lowerCamelCase Health Connect names: `steps`, `heartRate`, `exerciseSession`, `sleepSession`, `vo2Max`, …
Upstream HCGateway's v2 routes stay compatible.
</details>

<details>
<summary><b>Development</b></summary>

```sh
api/run-tests.sh                       # server tests against a throwaway MongoDB (Docker)
docker build -t healthbridge-api api   # server image
```

`api/` Flask + gunicorn server · `.env.example` all settings · `app/` Kotlin + Compose app (Health Connect `connect-client` 1.1) · `scripts/` maintenance · `docs/` assets.

CI builds and signs the APK and attaches it to every `v*` release (`.github/workflows/android.yml`) and publishes
`ghcr.io/mteij/healthbridge` (`docker.yml`). To sign with your own key set the secrets `HB_KEYSTORE_B64`, `HB_KEYSTORE_PASSWORD`, `HB_KEY_ALIAS`.
</details>

## Credits

Hard fork of [HCGateway](https://github.com/ShuchirJ/HCGateway) by Shuchir Jain. MIT licensed.
