from aggregate import build_workout


def rec(rid, start, end, app, **data):
    return {"id": rid, "start": start, "end": end, "app": app, "data": data}


RUN = rec("run1", "2026-10-20T10:00:00.000Z", "2026-10-20T10:30:00.000Z", "strava",
          exerciseType=56, title="Morning run")


def test_running_workout_joins_overlapping_records():
    series = {
        "distance": [rec("d1", "2026-10-20T10:00:00.000Z", "2026-10-20T10:30:00.000Z", "strava",
                         distance={"value": 5000, "unit": "meters"})],
        "heartRate": [rec("h1", "2026-10-20T10:00:00.000Z", "2026-10-20T10:30:00.000Z", "strava",
                          samples=[{"time": "2026-10-20T10:05:00Z", "beatsPerMinute": 140},
                                   {"time": "2026-10-20T10:15:00Z", "beatsPerMinute": 160},
                                   {"time": "2026-10-20T10:45:00Z", "beatsPerMinute": 70}])],
        "activeCaloriesBurned": [rec("c1", "2026-10-20T10:00:00.000Z", "2026-10-20T10:30:00.000Z", "strava",
                                     energy={"value": 300, "unit": "kilocalories"})],
    }
    w = build_workout(RUN, series)
    assert w["type"] == "running" and w["title"] == "Morning run"
    assert w["duration_s"] == 1800
    assert w["distance_m"] == 5000
    assert w["pace_s_per_km"] == 360
    assert w["heart_rate"] == {"avg": 150, "min": 140, "max": 160, "samples": 2}  # 10:45 excluded
    assert w["active_kcal"] == 300


def test_partially_overlapping_record_is_prorated():
    series = {"distance": [rec("d", "2026-10-20T09:30:00.000Z", "2026-10-20T10:30:00.000Z", "strava",
                               distance={"value": 10, "unit": "kilometers"})]}
    assert build_workout(RUN, series)["distance_m"] == 5000  # half of the hour-long record


def test_prefers_own_source_over_other_apps_to_avoid_double_counting():
    series = {"distance": [
        rec("a", "2026-10-20T10:00:00.000Z", "2026-10-20T10:30:00.000Z", "strava", distance={"value": 5000, "unit": "meters"}),
        rec("b", "2026-10-20T10:00:00.000Z", "2026-10-20T10:30:00.000Z", "phone", distance={"value": 4800, "unit": "meters"}),
    ]}
    assert build_workout(RUN, series)["distance_m"] == 5000


def test_falls_back_to_best_covering_source_and_unknown_fields_are_none():
    series = {"steps": [rec("s", "2026-10-20T10:00:00.000Z", "2026-10-20T10:30:00.000Z", "phone", count=4000)]}
    w = build_workout(RUN, series)
    assert w["steps"] == 4000
    assert w["distance_m"] is None and w["heart_rate"] is None and w["pace_s_per_km"] is None


def test_unit_conversion_and_samples_output():
    series = {"speed": [rec("sp", "2026-10-20T10:00:00.000Z", "2026-10-20T10:30:00.000Z", "strava",
                            samples=[{"time": "2026-10-20T10:10:00Z", "speed": {"value": 18, "unit": "kilometersPerHour"}}])]}
    w = build_workout(RUN, series, include_samples=True)
    assert w["avg_speed_mps"] == 5.0
    assert w["samples"]["speed"][0]["mps"] == 5.0


def test_unknown_exercise_type_is_labelled():
    s = rec("x", "2026-10-20T10:00:00.000Z", "2026-10-20T10:10:00.000Z", "a", exerciseType=999)
    assert build_workout(s, {})["type"] == "unknown_999"


def test_native_react_native_health_connect_shapes():
    series = {
        "distance": [rec("d", "2026-10-20T10:00:00.000Z", "2026-10-20T10:30:00.000Z", "strava",
                         distance={"inMeters": 5000.0, "inKilometers": 5.0, "inFeet": 16404.0})],
        "activeCaloriesBurned": [rec("c", "2026-10-20T10:00:00.000Z", "2026-10-20T10:30:00.000Z", "strava",
                                     energy={"inKilocalories": 310.5, "inJoules": 1.0})],
        "speed": [rec("s", "2026-10-20T10:00:00.000Z", "2026-10-20T10:30:00.000Z", "strava",
                      samples=[{"time": "2026-10-20T10:10:00Z", "speed": {"inMetersPerSecond": 3.0}}])],
    }
    w = build_workout(RUN, series)
    assert (w["distance_m"], w["active_kcal"], w["avg_speed_mps"]) == (5000.0, 310.5, 3.0)
