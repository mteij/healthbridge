"""Summaries for the app's dashboard: one request instead of a dozen."""
import datetime
import os

from flask import g, jsonify, request

import db
from aggregate import build_workout
from apiVersions.v2.records import decrypt_docs
from apiVersions.v2.workouts import load_sessions, series_for
from blueprint import v2
from crypto import data_cipher
from summaries import DAILY_METRICS, LATEST_TYPES, _tz, daily_totals, latest_value, sleep_sessions

DEFAULT_TZ = os.environ.get("HB_TIMEZONE", "Europe/Amsterdam")


def _cipher():
    return data_cipher(db.users().find_one({"_id": g.user}))


def _tz_name():
    name = request.args.get("tz") or DEFAULT_TZ
    return name if _tz(name).key == name else DEFAULT_TZ


def _dates():
    try:
        start = datetime.date.fromisoformat(request.args["from"])
        end = datetime.date.fromisoformat(request.args["to"])
    except (KeyError, ValueError):
        return None
    if end < start or (end - start).days > 366:
        return None
    return start, end


@v2.get("/overview")
def overview():
    tz = _tz_name()
    cipher = _cipher()
    today = datetime.datetime.now(_tz(tz)).date()
    week_start = today - datetime.timedelta(days=6)
    sessions = decrypt_docs(load_sessions(g.user, {}, 5), cipher)
    return jsonify({
        "date": today.isoformat(),
        "daily": {m: daily_totals(g.user, cipher, m, week_start, today, tz) for m in ("steps", "distance", "active_calories")},
        "sleep": sleep_sessions(g.user, cipher, today - datetime.timedelta(days=1), today, tz),
        "measurements": [latest_value(g.user, cipher, name) for name in sorted(LATEST_TYPES)],
        "recent_workouts": [build_workout(s, series_for(g.user, s, cipher)) for s in sessions],
    }), 200


@v2.get("/daily")
def daily():
    metric = request.args.get("metric", "")
    window = _dates()
    if metric not in DAILY_METRICS or window is None:
        return jsonify({"error": "metric, from and to (YYYY-MM-DD, at most 366 days) are required"}), 400
    return jsonify(daily_totals(g.user, _cipher(), metric, window[0], window[1], _tz_name())), 200


@v2.get("/sleep")
def sleep_nights():
    window = _dates()
    if window is None:
        return jsonify({"error": "from and to (YYYY-MM-DD, at most 366 days) are required"}), 400
    return jsonify(sleep_sessions(g.user, _cipher(), window[0], window[1], _tz_name())), 200


@v2.get("/measurements")
def measurements():
    cipher = _cipher()
    return jsonify([latest_value(g.user, cipher, name) for name in sorted(LATEST_TYPES)]), 200
