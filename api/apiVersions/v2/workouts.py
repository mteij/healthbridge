import logging

from flask import g, jsonify, request

import db
from blueprint import v2
from crypto import data_cipher
from apiVersions.v2.records import decrypt_docs
from timeutil import canonical, parse_iso, to_iso
from aggregate import build_workout, type_name

log = logging.getLogger("healthbridge.workouts")

SERIES_TYPES = ["heartRate", "speed", "distance", "activeCaloriesBurned",
                "totalCaloriesBurned", "steps", "elevationGained"]
DEFAULT_LIMIT, MAX_LIMIT = 20, 200
# records may be reported in chunks that begin before / end after the workout
MARGIN_MS = 3 * 3600 * 1000


def series_for(userid, session, cipher):
    from datetime import timedelta
    start, end = parse_iso(session["start"]), parse_iso(session["end"])
    lo = to_iso(start - timedelta(milliseconds=MARGIN_MS))
    hi = to_iso(end)
    series = {}
    for record_type in SERIES_TYPES:
        # anything that begins before the workout ends and was not finished before it began
        cursor = db.records(userid, record_type).find({"start": {"$gte": lo, "$lte": hi}})
        docs = decrypt_docs(cursor, cipher)
        series[record_type] = [d for d in docs if (d["end"] or d["start"]) >= session["start"]]
    return series


def load_sessions(userid, query, limit):
    return list(db.records(userid, "exerciseSession").find(query).sort("start", -1).limit(limit))


@v2.get("/workouts")
def workouts():
    args = request.args
    query = {}
    try:
        if args.get("from"):
            query.setdefault("start", {})["$gte"] = canonical(args["from"])
        if args.get("to"):
            query.setdefault("start", {})["$lte"] = canonical(args["to"])
        limit = max(1, min(int(args.get("limit", DEFAULT_LIMIT)), MAX_LIMIT))
    except (ValueError, TypeError):
        return jsonify({"error": "invalid from, to or limit"}), 400

    user = db.users().find_one({"_id": g.user})
    cipher = data_cipher(user)
    sessions = decrypt_docs(load_sessions(g.user, query, limit), cipher)

    wanted = args.get("type")
    include_samples = args.get("samples") in ("1", "true")
    out = []
    for session in sessions:
        if wanted and type_name(session["data"].get("exerciseType")) != wanted.lower():
            continue
        out.append(build_workout(session, series_for(g.user, session, cipher), include_samples))
    return jsonify(out), 200


@v2.get("/workouts/<workout_id>")
def workout_detail(workout_id):
    user = db.users().find_one({"_id": g.user})
    cipher = data_cipher(user)
    sessions = decrypt_docs(db.records(g.user, "exerciseSession").find({"_id": workout_id}), cipher)
    if not sessions:
        return jsonify({"error": "workout not found"}), 404
    session = sessions[0]
    return jsonify(build_workout(session, series_for(g.user, session, cipher), True)), 200
