"""Minimal MCP (Model Context Protocol) server over HTTP, mounted at /mcp.

Stateless JSON-RPC over POST (the "streamable HTTP" transport without server-initiated streams).
Authenticated with a read-only Healthbridge API key; every tool only reads data.
"""
import datetime
import json
import logging
import os

from flask import Blueprint, g, jsonify, request

import db
from aggregate import build_workout, type_name
from apiVersions.v2.auth import API_KEY_PREFIX, hash_key
from apiVersions.v2.records import decrypt_docs
from apiVersions.v2.workouts import load_sessions, series_for
from crypto import data_cipher
from summaries import DAILY_METRICS, LATEST_TYPES, daily_totals, latest_value, sleep_sessions
from timeutil import canonical, parse_iso, to_iso, utcnow

log = logging.getLogger("healthbridge.mcp")
mcp = Blueprint("mcp", __name__)

SERVER_VERSION = os.environ.get("HB_VERSION", "dev")
PROTOCOLS = ["2025-06-18", "2025-03-26", "2024-11-05"]
DEFAULT_TZ = os.environ.get("HB_TIMEZONE", "Europe/Amsterdam")

INSTRUCTIONS = (
    "Healthbridge exposes the user's personal health data synced from Android Health Connect "
    "(workouts, steps, heart rate, sleep, body measurements). All tools are read-only. "
    "Dates are YYYY-MM-DD in the user's timezone; timestamps are UTC ISO-8601. "
    "Start with get_overview for a quick picture, then drill down with list_workouts, "
    "get_workout, get_daily_totals or get_sleep. Treat the data as sensitive."
)

DATE = {"type": "string", "description": "Date, YYYY-MM-DD"}
TOOLS = [
    {
        "name": "get_overview",
        "description": "Quick snapshot: latest body measurements, the last 7 days of steps and active calories, last night's sleep and the 3 most recent workouts.",
        "inputSchema": {"type": "object", "properties": {"timezone": {"type": "string", "description": "IANA timezone, default Europe/Amsterdam"}}},
    },
    {
        "name": "list_workouts",
        "description": "List workouts (exercise sessions) with distance, duration, pace, heart rate and calories, newest first.",
        "inputSchema": {"type": "object", "properties": {
            "from": {"type": "string", "description": "Only workouts starting at or after this date or timestamp"},
            "to": {"type": "string", "description": "Only workouts starting at or before this date or timestamp"},
            "type": {"type": "string", "description": "Exercise type, e.g. running, biking, walking, strength_training"},
            "limit": {"type": "integer", "description": "Max results, default 20, max 100"},
        }},
    },
    {
        "name": "get_workout",
        "description": "One workout by id, including heart-rate and speed samples (downsampled to at most 120 points each).",
        "inputSchema": {"type": "object", "properties": {"id": {"type": "string"}}, "required": ["id"]},
    },
    {
        "name": "get_daily_totals",
        "description": "Per-day totals for a metric over a date range. Data from several apps is not double counted: each day uses its single most complete source.",
        "inputSchema": {"type": "object", "properties": {
            "metric": {"type": "string", "enum": sorted(DAILY_METRICS)},
            "from": DATE, "to": DATE,
            "timezone": {"type": "string"},
        }, "required": ["metric", "from", "to"]},
    },
    {
        "name": "get_sleep",
        "description": "Sleep sessions that ended within a date range, with minutes in bed, asleep and per stage (awake, light, deep, rem).",
        "inputSchema": {"type": "object", "properties": {"from": DATE, "to": DATE, "timezone": {"type": "string"}}, "required": ["from", "to"]},
    },
    {
        "name": "get_latest_measurements",
        "description": "Most recent value of body and vital measurements: " + ", ".join(sorted(LATEST_TYPES)) + ".",
        "inputSchema": {"type": "object", "properties": {"metrics": {"type": "array", "items": {"type": "string", "enum": sorted(LATEST_TYPES)}}}},
    },
]


class ToolError(Exception):
    pass


def _date(value, name):
    try:
        return datetime.date.fromisoformat(str(value))
    except (TypeError, ValueError):
        raise ToolError(f"'{name}' must be a date in YYYY-MM-DD format")


def _window(args):
    start, end = _date(args.get("from"), "from"), _date(args.get("to"), "to")
    if end < start:
        raise ToolError("'to' is before 'from'")
    if (end - start).days > 366:
        raise ToolError("range is limited to 366 days")
    return start, end


def _downsample(points, limit=120):
    if len(points) <= limit:
        return points
    step = len(points) / limit
    return [points[int(i * step)] for i in range(limit)]


def _workouts(userid, cipher, query, limit, wanted=None, samples=False):
    sessions = decrypt_docs(load_sessions(userid, query, limit), cipher)
    out = []
    for session in sessions:
        if wanted and type_name(session["data"].get("exerciseType")) != wanted.lower():
            continue
        w = build_workout(session, series_for(userid, session, cipher), samples)
        if samples:
            w["samples"] = {k: _downsample(v) for k, v in w["samples"].items()}
        out.append(w)
    return out


def tool_list_workouts(userid, cipher, args):
    query = {}
    try:
        if args.get("from"):
            query.setdefault("start", {})["$gte"] = canonical(args["from"])
        if args.get("to"):
            to = str(args["to"])
            # a bare date means "through the end of that day"
            query.setdefault("start", {})["$lte"] = canonical(to + "T23:59:59Z") if len(to) == 10 else canonical(to)
        limit = max(1, min(int(args.get("limit", 20)), 100))
    except (TypeError, ValueError):
        raise ToolError("invalid from, to or limit")
    return _workouts(userid, cipher, query, limit, args.get("type"))


def tool_get_workout(userid, cipher, args):
    docs = decrypt_docs(db.records(userid, "exerciseSession").find({"_id": str(args.get("id", ""))}), cipher)
    if not docs:
        raise ToolError("workout not found")
    w = build_workout(docs[0], series_for(userid, docs[0], cipher), True)
    w["samples"] = {k: _downsample(v) for k, v in w["samples"].items()}
    return w


def tool_daily_totals(userid, cipher, args):
    metric = args.get("metric")
    if metric not in DAILY_METRICS:
        raise ToolError(f"unknown metric; choose one of {sorted(DAILY_METRICS)}")
    start, end = _window(args)
    return daily_totals(userid, cipher, metric, start, end, args.get("timezone") or DEFAULT_TZ)


def tool_sleep(userid, cipher, args):
    start, end = _window(args)
    return sleep_sessions(userid, cipher, start, end, args.get("timezone") or DEFAULT_TZ)


def tool_latest(userid, cipher, args):
    names = args.get("metrics") or sorted(LATEST_TYPES)
    unknown = [n for n in names if n not in LATEST_TYPES]
    if unknown:
        raise ToolError(f"unknown metrics: {unknown}")
    return [latest_value(userid, cipher, n) for n in names]


def tool_overview(userid, cipher, args):
    tz = args.get("timezone") or DEFAULT_TZ
    today = datetime.datetime.now(datetime.timezone.utc).date()
    week_ago = today - datetime.timedelta(days=6)
    return {
        "latest": tool_latest(userid, cipher, {}),
        "last_7_days": {m: daily_totals(userid, cipher, m, week_ago, today, tz) for m in ("steps", "active_calories")},
        "sleep_last_2_days": sleep_sessions(userid, cipher, today - datetime.timedelta(days=1), today, tz),
        "recent_workouts": _workouts(userid, cipher, {}, 3),
    }


HANDLERS = {
    "get_overview": tool_overview,
    "list_workouts": tool_list_workouts,
    "get_workout": tool_get_workout,
    "get_daily_totals": tool_daily_totals,
    "get_sleep": tool_sleep,
    "get_latest_measurements": tool_latest,
}


def _result(msg_id, result):
    return {"jsonrpc": "2.0", "id": msg_id, "result": result}


def _error(msg_id, code, message):
    return {"jsonrpc": "2.0", "id": msg_id, "error": {"code": code, "message": message}}


def handle(message, userid):
    """Handle one JSON-RPC message; returns a response dict, or None for notifications."""
    if not isinstance(message, dict) or message.get("jsonrpc") != "2.0" or "method" not in message:
        return _error(message.get("id") if isinstance(message, dict) else None, -32600, "invalid request")
    method, msg_id, params = message["method"], message.get("id"), message.get("params") or {}
    if "id" not in message:
        return None  # notification, e.g. notifications/initialized

    if method == "initialize":
        asked = params.get("protocolVersion")
        version = asked if asked in PROTOCOLS else PROTOCOLS[0]
        return _result(msg_id, {
            "protocolVersion": version,
            "capabilities": {"tools": {"listChanged": False}},
            "serverInfo": {"name": "healthbridge", "version": SERVER_VERSION},
            "instructions": INSTRUCTIONS,
        })
    if method == "ping":
        return _result(msg_id, {})
    if method == "tools/list":
        return _result(msg_id, {"tools": TOOLS})
    if method == "tools/call":
        name, args = params.get("name"), params.get("arguments") or {}
        handler = HANDLERS.get(name)
        if handler is None:
            return _error(msg_id, -32602, f"unknown tool: {name}")
        try:
            user = db.users().find_one({"_id": userid})
            data = handler(userid, data_cipher(user), args)
            return _result(msg_id, {"content": [{"type": "text", "text": json.dumps(data, ensure_ascii=False)}], "isError": False})
        except ToolError as e:
            return _result(msg_id, {"content": [{"type": "text", "text": str(e)}], "isError": True})
        except Exception:
            log.exception("tool %s failed", name)
            return _result(msg_id, {"content": [{"type": "text", "text": "internal error"}], "isError": True})
    return _error(msg_id, -32601, f"method not found: {method}")


def _authenticate():
    header = request.headers.get("Authorization", "")
    parts = header.split(" ", 1)
    if len(parts) != 2 or parts[0].lower() != "bearer" or not parts[1].startswith(API_KEY_PREFIX):
        return None
    key = db.apikeys().find_one({"hash": hash_key(parts[1].strip())})
    if not key:
        return None
    db.apikeys().update_one({"_id": key["_id"]}, {"$set": {"lastUsed": utcnow()}})
    return key["user"]


@mcp.post("/mcp")
def mcp_endpoint():
    userid = _authenticate()
    if userid is None:
        resp = jsonify({"error": "a Healthbridge API key (hb_…) is required"})
        resp.status_code = 401
        resp.headers["WWW-Authenticate"] = 'Bearer realm="healthbridge"'
        return resp
    payload = request.get_json(silent=True)
    if payload is None:
        return jsonify(_error(None, -32700, "parse error")), 400
    if isinstance(payload, list):
        responses = [r for r in (handle(m, userid) for m in payload) if r is not None]
        return (jsonify(responses), 200) if responses else ("", 202)
    response = handle(payload, userid)
    return (jsonify(response), 200) if response is not None else ("", 202)


@mcp.get("/mcp")
def mcp_get():
    # no server-initiated stream is offered
    return jsonify({"error": "use POST"}), 405
