"""Rolls old fine-grained records up into hourly totals.

A phone writes steps, distance and calories every minute: roughly 300 records per type per day, almost
all of them never looked at individually. Records of at most 15 minutes that are older than
HB_ROLLUP_DAYS are merged into one record per source app and hour (`agg: true`), which cuts
storage for these types by roughly 10x. Records longer than 15 minutes (a workout's own distance
or calories) and everything newer than the cutoff stay exactly as they were.

Hours that overlap an exercise session are never rolled up: a workout's distance and calories are
read from the fine-grained records around it, and an hourly total would smear them.

Once an hour has been rolled up, records for it that arrive later are ignored: they would be
counted twice when the app re-imports a period.
"""
import datetime
import logging
import os

import db
from aggregate import ENERGY_KCAL, LENGTH_M, _measure
from apiVersions.v2.records import decrypt_docs
from crypto import data_cipher, encode_payload
from timeutil import parse_iso, to_iso, utcnow

log = logging.getLogger("healthbridge.rollup")

SMALL_SECONDS = 15 * 60
HOUR = datetime.timedelta(hours=1)

# record type -> (read value from a payload, build a payload from a total)
ROLLUP_TYPES = {
    "steps": (lambda d: _plain(d.get("count")), lambda v: {"count": round(v)}),
    "distance": (lambda d: _measure(d.get("distance"), LENGTH_M, "inMeters"), lambda v: {"distance": {"inMeters": v}}),
    "activeCaloriesBurned": (lambda d: _measure(d.get("energy"), ENERGY_KCAL, "inKilocalories"), lambda v: {"energy": {"inKilocalories": v}}),
    "totalCaloriesBurned": (lambda d: _measure(d.get("energy"), ENERGY_KCAL, "inKilocalories"), lambda v: {"energy": {"inKilocalories": v}}),
    "elevationGained": (lambda d: _measure(d.get("elevation"), LENGTH_M, "inMeters"), lambda v: {"elevation": {"inMeters": v}}),
    "floorsClimbed": (lambda d: _plain(d.get("floors")), lambda v: {"floors": v}),
}


def _plain(value):
    return float(value) if isinstance(value, (int, float)) else None


def rollup_days():
    raw = os.environ.get("HB_ROLLUP_DAYS", "")
    return int(raw) if raw.isdigit() else 0


def _hour_start(dt):
    return dt.replace(minute=0, second=0, microsecond=0)


def bucket_id(record_type, app, hour_start):
    return f"agg:{record_type}:{app}:{to_iso(hour_start)}"


def is_small(doc):
    if not doc.get("end"):
        return False
    return (parse_iso(doc["end"]) - parse_iso(doc["start"])).total_seconds() <= SMALL_SECONDS


def drop_rolled_up(userid, record_type, docs):
    """Ingestion guard: drop small records whose hour has already been rolled up."""
    if record_type not in ROLLUP_TYPES or rollup_days() <= 0:
        return docs
    keys = {}
    for doc in docs:
        if is_small(doc):
            keys[doc["_id"]] = bucket_id(record_type, doc.get("app"), _hour_start(parse_iso(doc["start"])))
    if not keys:
        return docs
    existing = {d["_id"] for d in db.records(userid, record_type).find({"_id": {"$in": list(set(keys.values()))}}, {"_id": 1})}
    return [d for d in docs if keys.get(d["_id"]) not in existing]


def _workout_hours(userid, since_iso, cutoff_iso):
    """Start of every hour touched by an exercise session in the range (from the plain fields only)."""
    query = {"start": {"$lt": cutoff_iso}, "end": {"$gte": since_iso or ""}}
    hours = set()
    for doc in db.records(userid, "exerciseSession").find(query, {"start": 1, "end": 1}):
        hour, last = _hour_start(parse_iso(doc["start"])), _hour_start(parse_iso(doc["end"] or doc["start"]))
        while hour <= last:
            hours.add(hour)
            hour += HOUR
    return hours


def rollup_user(user, now=None, days=None):
    """Returns (records removed, buckets written)."""
    days = rollup_days() if days is None else days
    if days <= 0:
        return 0, 0
    now = now or utcnow().replace(tzinfo=datetime.timezone.utc)
    cutoff = _hour_start(now - datetime.timedelta(days=days))
    cutoff_iso = to_iso(cutoff)
    userid, cipher = user["_id"], data_cipher(user)
    since = user.get("rollupDone")
    removed = written = 0
    protected = _workout_hours(userid, since, cutoff_iso)
    for record_type, (read, build) in ROLLUP_TYPES.items():
        collection = db.records(userid, record_type)
        query = {"start": {"$lt": cutoff_iso}, "agg": {"$ne": True}}
        if since:
            query["start"]["$gte"] = since
        groups = {}
        for doc in collection.find(query, {"start": 1, "end": 1, "app": 1}):
            if is_small(doc):
                hour = _hour_start(parse_iso(doc["start"]))
                if hour not in protected:
                    groups.setdefault((doc.get("app"), hour), []).append(doc["_id"])
        for (app, hour), ids in groups.items():
            total, used = 0.0, []
            for doc in decrypt_docs(collection.find({"_id": {"$in": ids}}), cipher):
                value = read(doc["data"])
                if value is not None:
                    total += value
                    used.append(doc["_id"])
            if not used:
                continue
            token, compressed = encode_payload(cipher, build(total))
            bucket = {"_id": bucket_id(record_type, app, hour), "data": token, "app": app,
                      "start": to_iso(hour), "end": to_iso(hour + HOUR), "agg": True}
            if compressed:
                bucket["z"] = True
            collection.replace_one({"_id": bucket["_id"]}, bucket, upsert=True)
            removed += collection.delete_many({"_id": {"$in": ids}}).deleted_count
            written += 1
    db.users().update_one({"_id": userid}, {"$set": {"rollupDone": cutoff_iso}})
    return removed, written


def run_rollup():
    if rollup_days() <= 0:
        return
    for user in list(db.users().find()):
        try:
            removed, written = rollup_user(user)
            if removed:
                log.info("rolled up %d records into %d hourly totals for %s", removed, written, user["username"])
        except Exception:
            log.exception("rollup failed for %s", user.get("username"))
