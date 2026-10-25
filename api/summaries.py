"""Read-side helpers shared by the REST API and the MCP server: daily totals, latest values, sleep."""
import datetime
from zoneinfo import ZoneInfo

import db
from aggregate import ENERGY_KCAL, LENGTH_M, _measure
from apiVersions.v2.records import decrypt_docs
from timeutil import parse_iso, to_iso

SLEEP_STAGES = {0: "unknown", 1: "awake", 2: "sleeping", 3: "out_of_bed", 4: "light", 5: "deep", 6: "rem", 7: "awake_in_bed"}

# metric -> (record type, field, unit table, native key, output unit, decimals)
DAILY_METRICS = {
    "steps": ("steps", "count", None, None, "steps", 0),
    "distance": ("distance", "distance", LENGTH_M, "inMeters", "m", 0),
    "active_calories": ("activeCaloriesBurned", "energy", ENERGY_KCAL, "inKilocalories", "kcal", 0),
    "total_calories": ("totalCaloriesBurned", "energy", ENERGY_KCAL, "inKilocalories", "kcal", 0),
    "floors": ("floorsClimbed", "floors", None, None, "floors", 1),
    "elevation": ("elevationGained", "elevation", LENGTH_M, "inMeters", "m", 0),
}

LATEST_TYPES = {
    "weight": ("weight", lambda d: _measure(d.get("weight"), {}, "inKilograms"), "kg"),
    "body_fat": ("bodyFat", lambda d: (d.get("percentage") or {}).get("value"), "%"),
    "resting_heart_rate": ("restingHeartRate", lambda d: d.get("beatsPerMinute"), "bpm"),
    "vo2_max": ("vo2Max", lambda d: d.get("vo2MillilitersPerMinuteKilogram"), "ml/min/kg"),
    "hrv_rmssd": ("heartRateVariabilityRmssd", lambda d: d.get("heartRateVariabilityMillis"), "ms"),
    "oxygen_saturation": ("oxygenSaturation", lambda d: (d.get("percentage") or {}).get("value"), "%"),
    "height": ("height", lambda d: _measure(d.get("height"), {}, "inMeters"), "m"),
}


def _tz(name):
    try:
        return ZoneInfo(name or "UTC")
    except Exception:
        return ZoneInfo("UTC")


def _value(doc, field, table, native):
    raw = doc["data"].get(field)
    if table is None:
        return float(raw) if isinstance(raw, (int, float)) else None
    return _measure(raw, table, native)


def daily_totals(userid, cipher, metric, start_date, end_date, tz_name=None):
    """Per local day total of `metric`. Several apps often report the same activity, so each
    day uses the single source with the highest total instead of adding them up."""
    record_type, field, table, native, unit, decimals = DAILY_METRICS[metric]
    tz = _tz(tz_name)
    first = datetime.datetime.combine(start_date, datetime.time.min, tz)
    last = datetime.datetime.combine(end_date + datetime.timedelta(days=1), datetime.time.min, tz)
    cursor = db.records(userid, record_type).find({"start": {"$gte": to_iso(first), "$lt": to_iso(last)}})
    per_day = {}
    for doc in decrypt_docs(cursor, cipher):
        value = _value(doc, field, table, native)
        if value is None:
            continue
        day = parse_iso(doc["start"]).astimezone(tz).date()
        bucket = per_day.setdefault(day, {})
        bucket[doc.get("app")] = bucket.get(doc.get("app"), 0.0) + value
    days = []
    for offset in range((end_date - start_date).days + 1):
        day = start_date + datetime.timedelta(days=offset)
        sources = per_day.get(day)
        if sources:
            app, total = max(sources.items(), key=lambda kv: kv[1])
            days.append({"date": day.isoformat(), "value": round(total, decimals) if decimals else int(round(total)),
                         "unit": unit, "source": app})
        else:
            days.append({"date": day.isoformat(), "value": None, "unit": unit, "source": None})
    return days


def latest_value(userid, cipher, name):
    record_type, extract, unit = LATEST_TYPES[name]
    docs = db.records(userid, record_type).find().sort("start", -1).limit(10)
    for doc in decrypt_docs(docs, cipher):
        value = extract(doc["data"])
        if value is not None:
            return {"metric": name, "value": round(float(value), 2), "unit": unit, "time": doc["start"], "source": doc.get("app")}
    return {"metric": name, "value": None, "unit": unit, "time": None, "source": None}


def sleep_sessions(userid, cipher, start_date, end_date, tz_name=None):
    tz = _tz(tz_name)
    # a night belongs to the day it ends on, so look a bit further back for the start
    first = datetime.datetime.combine(start_date - datetime.timedelta(days=1), datetime.time.min, tz)
    last = datetime.datetime.combine(end_date + datetime.timedelta(days=1), datetime.time.min, tz)
    cursor = db.records(userid, "sleepSession").find({"start": {"$gte": to_iso(first), "$lt": to_iso(last)}}).sort("start", 1)
    out = []
    for doc in decrypt_docs(cursor, cipher):
        start, end = parse_iso(doc["start"]), parse_iso(doc["end"] or doc["start"])
        wake_day = end.astimezone(tz).date()
        if not (start_date <= wake_day <= end_date):
            continue
        stages = {}
        for stage in doc["data"].get("stages", []):
            seconds = (parse_iso(stage["endTime"]) - parse_iso(stage["startTime"])).total_seconds()
            name = SLEEP_STAGES.get(stage.get("stage"), "unknown")
            stages[name] = stages.get(name, 0) + seconds
        asleep = sum(v for k, v in stages.items() if k in ("sleeping", "light", "deep", "rem"))
        out.append({
            "date": wake_day.isoformat(), "start": doc["start"], "end": doc["end"], "source": doc.get("app"),
            "in_bed_min": round((end - start).total_seconds() / 60),
            "asleep_min": round(asleep / 60) if stages else None,
            "stages_min": {k: round(v / 60) for k, v in stages.items()},
        })
    return out


def measurement_series(userid, cipher, name, start_date, end_date, tz_name=None):
    """One value per day (the last of that day) for a body measurement, only days that have one."""
    record_type, extract, unit = LATEST_TYPES[name]
    tz = _tz(tz_name)
    first = datetime.datetime.combine(start_date, datetime.time.min, tz)
    last = datetime.datetime.combine(end_date + datetime.timedelta(days=1), datetime.time.min, tz)
    cursor = db.records(userid, record_type).find({"start": {"$gte": to_iso(first), "$lt": to_iso(last)}}).sort("start", 1)
    per_day = {}
    for doc in decrypt_docs(cursor, cipher):
        value = extract(doc["data"])
        if value is not None:
            per_day[parse_iso(doc["start"]).astimezone(tz).date()] = round(float(value), 2)
    return [{"date": d.isoformat(), "value": v, "unit": unit} for d, v in sorted(per_day.items())]
