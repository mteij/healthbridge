<div align="center">

<img src="docs/logo.svg" width="80" alt="">

# Healthbridge

Sync Android Health Connect to your own server. REST API and MCP included.

[![Release](https://img.shields.io/github/v/release/mteij/healthbridge?style=flat-square&color=0b6e8a)](../../releases/latest)
[![License](https://img.shields.io/github/license/mteij/healthbridge?style=flat-square)](LICENSE)

[<img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" height="48" alt="Get it on Obtainium">](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/mteij/healthbridge)
&nbsp;
[**Download APK**](../../releases/latest) · [**Public instance**](https://healthbridge.mteij.nl)

</div>

## Install

1. Install the app (Android 9+, Health Connect).
2. Sign in with a server address, username and password. The first sign-in creates the account.
3. Follow the checklist on the home screen.

## Server

**Public instance:** https://healthbridge.mteij.nl. Best effort, first 500 users, accounts inactive for 30 days are deleted. The operator can read stored data; self-host for anything sensitive.

**Self-host** (Docker):

```sh
curl -fsSL https://raw.githubusercontent.com/mteij/healthbridge/main/docker-compose.yml | docker compose -p healthbridge -f - up -d
```

Port `6644`, MongoDB not exposed. Put an HTTPS reverse proxy in front; the app requires `https://`. The first account becomes the owner and registration then closes. Settings: [`.env.example`](.env.example).

## API keys

Read-only, shown once:

```sh
curl -s -X POST https://your.server/api/v2/login -H 'Content-Type: application/json' -d '{"username":"you","password":"…"}'
curl -s -X POST https://your.server/api/v2/apikeys -H "Authorization: Bearer <token>" -H 'Content-Type: application/json' -d '{"name":"agent"}'
```

Use as `Authorization: Bearer hb_…`.

## MCP

```sh
claude mcp add --transport http healthbridge https://your.server/mcp --header "Authorization: Bearer hb_…"
```

Tools (read-only): `get_overview`, `list_workouts`, `get_workout`, `get_daily_totals`, `get_sleep`, `get_latest_measurements`.

## REST

Under `/api/v2`, session or API key (keys: read endpoints only).

| | |
|---|---|
| `POST /login` `/refresh` · `DELETE /revoke` | Session |
| `POST /sync/<type>` · `DELETE /sync/<type>` | Upload or delete records |
| `POST /fetch/<type>` | Records: `from`, `to`, `limit`, `cursor` (`X-Next-Cursor`) |
| `GET /workouts` `/workouts/<id>` | Workouts joined with heart rate, distance, energy |
| `GET /overview` `/daily` `/sleep` `/measurements` | Dashboard data |
| `POST/GET/DELETE /apikeys` | Manage keys |
| `GET /healthz` `/status` | Health, public status |

Types are lowerCamelCase Health Connect names (`steps`, `heartRate`, `exerciseSession`, `vo2Max`, …).

## Development

```sh
api/run-tests.sh   # server tests, needs Docker
```

`api/` server · `app/` Android app · `scripts/` maintenance. Tag `v*` builds the signed APK and the Docker image.

## License

GPL-3.0. Based on [HCGateway](https://github.com/ShuchirJ/HCGateway) by Shuchir Jain.
