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


# ACSM (10th ed.) intensity classes as a share of maximal heart rate:
# light < 64 %, moderate 64-76 %, vigorous 77-95 %, near maximal >= 96 %.
ZONE_LIMITS = (0.64, 0.77, 0.96)
MAX_SAMPLE_GAP_S = 120


def zone_seconds(hr_points, max_hr):
    """Seconds in [light, moderate, vigorous, peak]. Each sample counts until the next one, at most 2 min."""
    seconds = [0.0, 0.0, 0.0, 0.0]
    for (t0, bpm), (t1, _) in zip(hr_points, hr_points[1:]):
        gap = min((t1 - t0).total_seconds(), MAX_SAMPLE_GAP_S)
        share = bpm / max_hr
        seconds[sum(share >= limit for limit in ZONE_LIMITS)] += gap
    return seconds


def intensity_minutes(zones):
    """WHO counts a vigorous minute as two moderate minutes (150-300 moderate = 75-150 vigorous per week)."""
    return (zones[1] + 2 * (zones[2] + zones[3])) / 60


def compute_splits(speed_points, hr_points, distance_m=None):
    """Per-kilometre times by integrating speed over time. Returns [] when the speed samples do not
    cover the workout well enough to trust (gaps, or a total that disagrees with the distance)."""
    if len(speed_points) < 3:
        return []
    covered, travelled = 0.0, [0.0]
    for (t0, v0), (t1, _) in zip(speed_points, speed_points[1:]):
        gap = (t1 - t0).total_seconds()
        if gap > 30:
            return []
        covered += gap
        travelled.append(travelled[-1] + v0 * gap)
    total = travelled[-1]
    if total < 1000 or (distance_m and abs(total - distance_m) / distance_m > 0.2):
        return []
    splits, next_km, last_time = [], 1000.0, speed_points[0][0]
    for i in range(1, len(speed_points)):
        while travelled[i] >= next_km:
            span = travelled[i] - travelled[i - 1]
            frac = (next_km - travelled[i - 1]) / span if span else 1.0
            t0, t1 = speed_points[i - 1][0], speed_points[i][0]
            crossing = t0 + (t1 - t0) * frac
            seconds = (crossing - last_time).total_seconds()
            hrs = [b for t, b in hr_points if last_time <= t <= crossing]
            splits.append({"km": len(splits) + 1, "seconds": round(seconds),
                           "pace_s_per_km": round(seconds), "avg_hr": round(sum(hrs) / len(hrs)) if hrs else None})
            last_time, next_km = crossing, next_km + 1000.0
    rest = total - (next_km - 1000.0)
    if rest >= 100:
        seconds = (speed_points[-1][0] - last_time).total_seconds()
        hrs = [b for t, b in hr_points if t >= last_time]
        splits.append({"km": round(rest / 1000, 2), "seconds": round(seconds), "partial": True,
                       "pace_s_per_km": round(seconds / (rest / 1000)), "avg_hr": round(sum(hrs) / len(hrs)) if hrs else None})
    return splits


STOP_SPEED_MPS = 0.5
MAX_SPEED_GAP_S = 30
MIN_PAUSE_S = 10
BEST_EFFORT_DISTANCES = (1000, 1609.344, 3000, 5000, 10000, 21097.5, 42195)
SEGMENT_TYPES = {
    1: "arm_curl", 2: "back_extension", 3: "ball_slam", 4: "barbell_shoulder_press", 5: "bench_press",
    6: "bench_sit_up", 7: "biking", 8: "biking_stationary", 9: "burpee", 10: "crunch", 11: "deadlift",
    12: "double_arm_triceps_extension", 13: "dumbbell_curl_left_arm", 14: "dumbbell_curl_right_arm",
    15: "dumbbell_front_raise", 16: "dumbbell_lateral_raise", 17: "dumbbell_row",
    18: "dumbbell_triceps_extension_left_arm", 19: "dumbbell_triceps_extension_right_arm",
    20: "dumbbell_triceps_extension_two_arm", 21: "elliptical", 22: "forward_twist", 23: "front_raise",
    24: "high_intensity_interval_training", 25: "hip_thrust", 26: "hula_hoop", 27: "jumping_jack",
    28: "jump_rope", 29: "kettlebell_swing", 30: "lateral_raise", 31: "lat_pull_down", 32: "leg_curl",
    33: "leg_extension", 34: "leg_press", 35: "leg_raise", 36: "lunge", 37: "mountain_climber",
    38: "other_workout", 39: "pause", 40: "pilates", 41: "plank", 42: "pull_up", 43: "punch",
    44: "rest", 45: "rowing_machine", 46: "running", 47: "running_treadmill", 48: "shoulder_press",
    49: "single_arm_triceps_extension", 50: "sit_up", 51: "squat", 52: "stair_climbing",
    53: "stair_climbing_machine", 54: "stretching", 55: "swimming_backstroke", 56: "swimming_breaststroke",
    57: "swimming_butterfly", 58: "swimming_freestyle", 59: "swimming_mixed", 60: "swimming_open_water",
    61: "swimming_other", 62: "swimming_pool", 63: "upper_twist", 64: "walking", 65: "weightlifting",
    66: "wheelchair", 67: "yoga",
}


def movement(speed):
    """Moving time and pauses from speed samples.

    A stretch counts as a pause when the speed stays under 0.5 m/s, or when the watch recorded
    nothing for more than 30 s (auto-pause stops the samples). Returns None without enough samples.
    """
    if len(speed) < 10:
        return None
    moving, pauses = 0.0, []
    for (t0, v0), (t1, _) in zip(speed, speed[1:]):
        gap = (t1 - t0).total_seconds()
        stopped = gap > MAX_SPEED_GAP_S or v0 < STOP_SPEED_MPS
        if not stopped:
            moving += gap
        elif pauses and (t0 - pauses[-1][1]).total_seconds() <= 1:
            pauses[-1][1] = t1
        else:
            pauses.append([t0, t1])
    pauses = [(a, b) for a, b in pauses if (b - a).total_seconds() >= MIN_PAUSE_S]
    return {"moving_s": round(moving), "paused_s": round(sum((b - a).total_seconds() for a, b in pauses)),
            "pauses": [{"start": to_iso(a), "end": to_iso(b)} for a, b in pauses]}


def cumulative_distance(speed):
    """[(time, metres so far)] by integrating speed; gaps longer than 30 s add no distance."""
    out, total = [(speed[0][0], 0.0)], 0.0
    for (t0, v0), (t1, _) in zip(speed, speed[1:]):
        gap = (t1 - t0).total_seconds()
        if gap <= MAX_SPEED_GAP_S:
            total += v0 * gap
        out.append((t1, total))
    return out


def best_efforts(speed, distance_m=None):
    """Fastest stretch of each standard distance inside one workout (sliding window over distance)."""
    if len(speed) < 10:
        return []
    track = cumulative_distance(speed)
    total = track[-1][1]
    if distance_m and abs(total - distance_m) / distance_m > 0.2:
        return []
    efforts = []
    for target in BEST_EFFORT_DISTANCES:
        if total < target:
            break
        best, j = None, 0
        for i in range(len(track)):
            while j < len(track) and track[j][1] - track[i][1] < target:
                j += 1
            if j >= len(track):
                break
            (tp, dp), (tj, dj) = track[j - 1], track[j]
            frac = (track[i][1] + target - dp) / (dj - dp) if dj > dp else 1.0
            seconds = (tp + (tj - tp) * frac - track[i][0]).total_seconds()
            if seconds > 0 and (best is None or seconds < best):
                best = seconds
        if best is not None:
            efforts.append({"distance_m": target, "seconds": round(best, 1)})
    return efforts


def cadence_stats(cadence_points, steps, moving_s, duration):
    """Average and maximum steps per minute; from cadence samples if the watch wrote them,
    otherwise from the step count over the moving time."""
    if cadence_points:
        values = [v for _, v in cadence_points]
        return {"avg": round(sum(values) / len(values)), "max": round(max(values))}
    seconds = moving_s or duration
    if steps and seconds and seconds > 60:
        return {"avg": round(steps / (seconds / 60)), "max": None}
    return None


def route_points(data):
    """Decoded GPS route: [(time, lat, lon, alt)] with bad fixes dropped."""
    out = []
    for p in data.get("route") or []:
        try:
            lat, lon = float(p["lat"]), float(p["lon"])
        except (KeyError, TypeError, ValueError):
            continue
        if not (-90 <= lat <= 90 and -180 <= lon <= 180):
            continue
        alt = p.get("alt")
        out.append((p.get("time"), lat, lon, float(alt) if isinstance(alt, (int, float)) else None))
    return out


def elevation_profile(points, window=5):
    """Gain, loss, minimum and maximum altitude. GPS altitude is noisy, so it is smoothed first
    and only changes above 1 m between smoothed neighbours count."""
    alts = [p[3] for p in points if p[3] is not None]
    if len(alts) < 10:
        return None
    smooth = [sum(alts[max(0, i - window):i + window + 1]) / len(alts[max(0, i - window):i + window + 1]) for i in range(len(alts))]
    gain = loss = 0.0
    ref = smooth[0]
    for a in smooth[1:]:
        if abs(a - ref) >= 1.0:
            if a > ref:
                gain += a - ref
            else:
                loss += ref - a
            ref = a
    return {"gain_m": round(gain), "loss_m": round(loss), "min_m": round(min(smooth)), "max_m": round(max(smooth))}


def downsample_route(points, limit=500):
    if len(points) <= limit:
        return points
    step = (len(points) - 1) / (limit - 1)
    return [points[round(i * step)] for i in range(limit)]


def build_laps(data, speed, hr):
    laps = []
    for i, lap in enumerate(data.get("laps") or [], 1):
        try:
            start, end = parse_iso(lap["startTime"]), parse_iso(lap["endTime"])
        except (KeyError, ValueError, TypeError):
            continue
        seconds = (end - start).total_seconds()
        if seconds <= 0:
            continue
        distance = _measure(lap.get("length"), LENGTH_M, "inMeters")
        if distance is None:
            window = [(t, v) for t, v in speed if start <= t <= end]
            if len(window) >= 3:
                distance = sum(v0 * (t1 - t0).total_seconds() for (t0, v0), (t1, _) in zip(window, window[1:])
                               if (t1 - t0).total_seconds() <= MAX_SPEED_GAP_S) or None
        hrs = [b for t, b in hr if start <= t <= end]
        laps.append({"lap": i, "start": to_iso(start), "seconds": round(seconds),
                     "distance_m": round(distance) if distance else None,
                     "pace_s_per_km": round(seconds / (distance / 1000)) if distance and distance > 50 else None,
                     "avg_hr": round(sum(hrs) / len(hrs)) if hrs else None})
    return laps


def build_segments(data):
    out = []
    for seg in data.get("segments") or []:
        try:
            start, end = parse_iso(seg["startTime"]), parse_iso(seg["endTime"])
        except (KeyError, ValueError, TypeError):
            continue
        out.append({"type": SEGMENT_TYPES.get(seg.get("segmentType"), f"segment_{seg.get('segmentType')}"),
                    "start": to_iso(start), "seconds": round((end - start).total_seconds()),
                    "repetitions": seg.get("repetitions") or None})
    return out



def build_workout(session, series, include_samples=False, max_hr=None):
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

    cadence = _samples(series.get("stepsCadence", []), "samples", w_start, w_end, app, lambda s: s.get("rate"))
    route = route_points(data)

    distance = _sum(series.get("distance", []), "distance", LENGTH_M, "inMeters", w_start, w_end, app)
    avg_speed = sum(v for _, v in speed) / len(speed) if speed else None
    if avg_speed is None and distance and duration > 0:
        avg_speed = distance / duration

    workout = {
        "id": session.get("id") or session["_id"],
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
        "moving_s": None,
        "paused_s": None,
        "cadence": None,
        "has_route": bool(route),
        "elevation": None,
        "best_efforts": [],
        "laps": build_laps(data, speed, hr),
        "segments": build_segments(data),
    }
    moved = movement(speed)
    if moved:
        workout["moving_s"], workout["paused_s"] = moved["moving_s"], moved["paused_s"]
    workout["cadence"] = cadence_stats(cadence, workout["steps"], workout["moving_s"], duration)
    workout["elevation"] = elevation_profile(route)
    if kind in PACE_TYPES:
        workout["best_efforts"] = best_efforts(speed, distance)
    if hr:
        values = [v for _, v in hr]
        workout["heart_rate"] = {"avg": round(sum(values) / len(values)), "min": min(values),
                                 "max": max(values), "samples": len(values)}
    if kind in PACE_TYPES and workout["distance_m"]:
        workout["pace_s_per_km"] = round(duration / (workout["distance_m"] / 1000))
    if max_hr and hr:
        zones = zone_seconds(hr, max_hr)
        workout["zones_s"] = [round(z) for z in zones]
        workout["intensity_min"] = round(intensity_minutes(zones), 1)
    if include_samples:
        workout["pauses"] = moved["pauses"] if moved else []
        workout["route"] = [
            {"t": t, "lat": round(lat, 6), "lon": round(lon, 6), "alt": round(alt, 1) if alt is not None else None}
            for t, lat, lon, alt in downsample_route(route)
        ]
        workout["splits"] = compute_splits(speed, hr, distance)
        workout["samples"] = {
            "heart_rate": [{"t": to_iso(t), "bpm": v} for t, v in hr],
            "speed": [{"t": to_iso(t), "mps": round(v, 3)} for t, v in speed],
        }
    return workout


def _richness(w):
    """How much a workout record knows; the fullest copy wins when two apps report the same session."""
    keys = ("distance_m", "heart_rate", "active_kcal", "total_kcal", "steps", "elevation_gain_m", "avg_speed_mps")
    return sum(w.get(k) is not None for k in keys) * 10 + (5 if w.get("title") else 0) + (1 if w.get("notes") else 0)


def dedupe_workouts(workouts, min_overlap=0.7):
    """Drop copies of the same session written by different apps (Strava and Samsung Health both
    exporting one run). Two workouts are one session when they overlap for at least `min_overlap`
    of the shorter one; a person cannot do two workouts at once. Input order is kept."""
    spans = [(parse_iso(w["start"]), parse_iso(w["end"])) for w in workouts]
    order = sorted(range(len(workouts)), key=lambda i: (-_richness(workouts[i]), -workouts[i]["duration_s"], i))
    kept = []
    for i in order:
        start, end = spans[i]
        shorter = lambda j: min((end - start).total_seconds(), (spans[j][1] - spans[j][0]).total_seconds())
        duplicate = False
        for j in kept:
            overlap = (min(end, spans[j][1]) - max(start, spans[j][0])).total_seconds()
            if overlap > 0 and overlap >= min_overlap * max(shorter(j), 1):
                duplicate = True
                break
        if not duplicate:
            kept.append(i)
    return [workouts[i] for i in sorted(kept)]


def _round(value, digits=1):
    if value is None:
        return None
    return int(round(value)) if digits == 0 else round(value, digits)
