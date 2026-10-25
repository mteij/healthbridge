"""Turns raw Health Connect records into workouts.

Health Connect stores a workout as an ExerciseSession (type, start, end) and keeps
heart rate, distance, speed and energy as separate records that merely overlap it
in time. This module joins them back together.
"""
from timeutil import parse_iso, to_iso

EXERCISE_TYPES = {
    0: "other_workout", 2: "badminton", 4: "baseball", 5: "basketball", 8: "biking",
    9: "biking_stationary", 10: "boot_camp", 11: "boxing", 13: "calisthenics",
    14: "cricket", 16: "dancing", 25: "elliptical", 26: "exercise_class", 27: "fencing",
    28: "football_american", 29: "football_australian", 31: "frisbee_disc", 32: "golf",
    33: "guided_breathing", 34: "gymnastics", 35: "handball", 36: "hiit", 37: "hiking",
    38: "ice_hockey", 39: "ice_skating", 44: "martial_arts", 46: "paddling",
    47: "paragliding", 48: "pilates", 50: "racquetball", 51: "rock_climbing",
    52: "roller_hockey", 53: "rowing", 54: "rowing_machine", 55: "rugby", 56: "running",
    57: "running_treadmill", 58: "sailing", 59: "scuba_diving", 60: "skating",
    61: "skiing", 62: "snowboarding", 63: "snowshoeing", 64: "soccer", 65: "softball",
    66: "squash", 68: "stair_climbing", 69: "stair_climbing_machine",
    70: "strength_training", 71: "stretching", 72: "surfing", 73: "swimming_open_water",
    74: "swimming_pool", 75: "table_tennis", 76: "tennis", 78: "volleyball",
    79: "walking", 80: "water_polo", 81: "weightlifting", 82: "wheelchair", 83: "yoga",
}
PACE_TYPES = {"running", "running_treadmill", "walking", "hiking"}

LENGTH_M = {"meters": 1.0, "kilometers": 1000.0, "miles": 1609.344, "feet": 0.3048, "inches": 0.0254}
ENERGY_KCAL = {"kilocalories": 1.0, "calories": 0.001, "kilojoules": 0.239006, "joules": 0.000239006}
SPEED_MPS = {"metersPerSecond": 1.0, "kilometersPerHour": 1 / 3.6, "milesPerHour": 0.44704}


def type_name(code):
    if isinstance(code, str):
        return code.lower()
    return EXERCISE_TYPES.get(code, f"unknown_{code}")


def _measure(value, table, native):
    """Read a quantity in the base unit.

    Handles the shape react-native-health-connect produces (`{"inMeters": 3.7, ...}`)
    as well as `{"value": 5, "unit": "kilometers"}` and bare numbers.
    """
    if isinstance(value, (int, float)):
        return float(value)
    if not isinstance(value, dict):
        return None
    if native in value:
        try:
            return float(value[native])
        except (TypeError, ValueError):
            return None
    if "value" not in value:
        return None
    factor = table.get(value.get("unit"), 1.0 if value.get("unit") is None else None)
    if factor is None:
        return None
    try:
        return float(value["value"]) * factor
    except (TypeError, ValueError):
        return None


def _span(record):
    start = parse_iso(record["start"])
    end = parse_iso(record["end"]) if record.get("end") else start
    return start, end


def _overlap_fraction(record, w_start, w_end):
    """Share of `record` that lies inside the window (point records count fully if inside)."""
    r_start, r_end = _span(record)
    if r_end <= r_start:
        return 1.0 if w_start <= r_start <= w_end else 0.0
    inter = min(r_end, w_end) - max(r_start, w_start)
    seconds = inter.total_seconds()
    if seconds <= 0:
        return 0.0
    return min(1.0, seconds / (r_end - r_start).total_seconds())


def _pick_source(records, w_start, w_end, preferred_app):
    """Several apps often report the same distance or energy; summing them double counts.

    Use the workout's own source if it has data, otherwise the source covering most of it.
    """
    usable = [r for r in records if _overlap_fraction(r, w_start, w_end) > 0]
    if not usable:
        return []
    by_app = {}
    for r in usable:
        by_app.setdefault(r.get("app"), []).append(r)
    if preferred_app in by_app:
        return by_app[preferred_app]
    return max(by_app.values(), key=lambda rs: sum(_overlap_fraction(r, w_start, w_end) for r in rs))


def _sum(records, field, table, native, w_start, w_end, preferred_app):
    chosen = _pick_source(records, w_start, w_end, preferred_app)
    total, found = 0.0, False
    for r in chosen:
        value = _measure(r["data"].get(field), table, native) if table else r["data"].get(field)
        if value is None:
            continue
        total += value * _overlap_fraction(r, w_start, w_end)
        found = True
    return total if found else None


def _samples(records, key, w_start, w_end, preferred_app, value_of):
    chosen = _pick_source(records, w_start, w_end, preferred_app)
    out = []
    for r in chosen:
        for s in r["data"].get("samples", []):
            try:
                t = parse_iso(s["time"])
            except (KeyError, ValueError, TypeError):
                continue
            if w_start <= t <= w_end:
                v = value_of(s)
                if v is not None:
                    out.append((t, v))
    out.sort(key=lambda x: x[0])
    return out


def build_workout(session, series, include_samples=False):
    """`session` is a decoded exerciseSession doc; `series` maps record type -> decoded docs."""
    w_start, w_end = _span(session)
    app = session.get("app")
    data = session["data"]
    kind = type_name(data.get("exerciseType"))
    duration = (w_end - w_start).total_seconds()

    hr = _samples(series.get("heartRate", []), "samples", w_start, w_end, app,
                  lambda s: s.get("beatsPerMinute"))
    speed = _samples(series.get("speed", []), "samples", w_start, w_end, app,
                     lambda s: _measure(s.get("speed"), SPEED_MPS, "inMetersPerSecond"))

    distance = _sum(series.get("distance", []), "distance", LENGTH_M, "inMeters", w_start, w_end, app)
    avg_speed = sum(v for _, v in speed) / len(speed) if speed else None
    if avg_speed is None and distance and duration > 0:
        avg_speed = distance / duration

    workout = {
        "id": session["id"],
        "type": kind,
        "title": data.get("title") or None,
        "notes": data.get("notes") or None,
        "source": app,
        "start": session["start"],
        "end": session["end"],
        "duration_s": round(duration),
        "distance_m": round(distance, 1) if distance is not None else None,
        "active_kcal": _round(_sum(series.get("activeCaloriesBurned", []), "energy", ENERGY_KCAL, "inKilocalories", w_start, w_end, app)),
        "total_kcal": _round(_sum(series.get("totalCaloriesBurned", []), "energy", ENERGY_KCAL, "inKilocalories", w_start, w_end, app)),
        "steps": _round(_sum(series.get("steps", []), "count", None, None, w_start, w_end, app), 0),
        "elevation_gain_m": _round(_sum(series.get("elevationGained", []), "elevation", LENGTH_M, "inMeters", w_start, w_end, app)),
        "heart_rate": None,
        "avg_speed_mps": _round(avg_speed, 3),
        "max_speed_mps": _round(max((v for _, v in speed), default=None), 3),
        "pace_s_per_km": None,
    }
    if hr:
        values = [v for _, v in hr]
        workout["heart_rate"] = {"avg": round(sum(values) / len(values)), "min": min(values),
                                 "max": max(values), "samples": len(values)}
    if kind in PACE_TYPES and workout["distance_m"]:
        workout["pace_s_per_km"] = round(duration / (workout["distance_m"] / 1000))
    if include_samples:
        workout["samples"] = {
            "heart_rate": [{"t": to_iso(t), "bpm": v} for t, v in hr],
            "speed": [{"t": to_iso(t), "mps": round(v, 3)} for t, v in speed],
        }
    return workout


def _round(value, digits=1):
    if value is None:
        return None
    return int(round(value)) if digits == 0 else round(value, digits)
