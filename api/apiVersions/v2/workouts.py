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
# a record can begin well before the workout and still overlap it (an hour of steps, a long
# heart-rate record); the range query below only uses this to bound the index scan
MARGIN_MS = 24 * 3600 * 1000


def series_for(userid, session, cipher):
    """Records overlapping the session, per type. Overlap is decided in MongoDB, so only the
    records that matter are decrypted."""
    from datetime import timedelta
    start, end = parse_iso(session["start"]), parse_iso(session["end"])
    lo = to_iso(start - timedelta(milliseconds=MARGIN_MS))
    hi = to_iso(end)
    series = {}
    for record_type in SERIES_TYPES:
        cursor = db.records(userid, record_type).find({
            "start": {"$gte": lo, "$lte": hi},
            "$or": [{"end": {"$gte": session["start"]}},
                    {"end": None, "start": {"$gte": session["start"]}}],
        })
        series[record_type] = decrypt_docs(cursor, cipher)
    return series


def downsample(points, limit):
    if len(points) <= limit:
        return points
    step = len(points) / limit
    return [points[int(i * step)] for i in range(limit)]


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
    workout = build_workout(session, series_for(g.user, session, cipher), True)
    try:
        points = int(request.args.get("points", 0))
    except ValueError:
        return jsonify({"error": "invalid points"}), 400
    if points > 0:
        workout["samples"] = {k: downsample(v, points) for k, v in workout["samples"].items()}
    return jsonify(workout), 200
