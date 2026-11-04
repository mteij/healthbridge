from conftest import auth


def test_workouts_endpoint_end_to_end(client, session):
    t = session["token"]
    origin = {"dataOrigin": "strava"}
    client.post("/api/v2/sync/exerciseSession", headers=auth(t), json={"data": [
        {"metadata": {"id": "run1", **origin}, "startTime": "2026-10-20T10:00:00Z",
         "endTime": "2026-10-20T10:30:00Z", "exerciseType": 56, "title": "Run"}]})
    client.post("/api/v2/sync/distance", headers=auth(t), json={"data": [
        {"metadata": {"id": "d1", **origin}, "startTime": "2026-10-20T10:00:00Z",
         "endTime": "2026-10-20T10:30:00Z", "distance": {"value": 6, "unit": "kilometers"}}]})
    client.post("/api/v2/sync/heartRate", headers=auth(t), json={"data": [
        {"metadata": {"id": "h1", **origin}, "startTime": "2026-10-20T10:00:00Z",
         "endTime": "2026-10-20T10:30:00Z",
         "samples": [{"time": "2026-10-20T10:10:00Z", "beatsPerMinute": 150}]}]})
    key = client.post("/api/v2/apikeys", json={"name": "k"}, headers=auth(t)).get_json()["key"]

    res = client.get("/api/v2/workouts?type=running", headers=auth(key))
    [w] = res.get_json()
    assert w["distance_m"] == 6000 and w["heart_rate"]["avg"] == 150 and w["pace_s_per_km"] == 300

    assert client.get("/api/v2/workouts?type=cycling", headers=auth(key)).get_json() == []
    assert client.get("/api/v2/workouts?from=2026-10-21T00:00:00Z", headers=auth(key)).get_json() == []
    detail = client.get("/api/v2/workouts/run1", headers=auth(key)).get_json()
    assert detail["samples"]["heart_rate"][0]["bpm"] == 150
    assert client.get("/api/v2/workouts/nope", headers=auth(key)).status_code == 404
    assert client.get("/api/v2/workouts?limit=abc", headers=auth(key)).status_code == 400


def test_dashboard_endpoints_for_the_app(client, session):
    import datetime
    t = session["token"]
    today = datetime.datetime.now(datetime.timezone.utc).date().isoformat()
    client.post("/api/v2/sync/steps", headers=auth(t), json={"data": [
        {"metadata": {"id": "s1", "dataOrigin": "phone"}, "startTime": f"{today}T01:00:00Z", "endTime": f"{today}T01:30:00Z", "count": 4200}]})
    samples = [{"time": f"{today}T10:{m:02d}:00Z", "beatsPerMinute": 100 + m} for m in range(50)]
    client.post("/api/v2/sync/exerciseSession", headers=auth(t), json={"data": [
        {"metadata": {"id": "w1", "dataOrigin": "phone"}, "startTime": f"{today}T10:00:00Z", "endTime": f"{today}T10:50:00Z", "exerciseType": 56}]})
    client.post("/api/v2/sync/heartRate", headers=auth(t), json={"data": [
        {"metadata": {"id": "h1", "dataOrigin": "phone"}, "startTime": f"{today}T10:00:00Z", "endTime": f"{today}T10:50:00Z", "samples": samples}]})
    key = client.post("/api/v2/apikeys", json={"name": "k"}, headers=auth(t)).get_json()["key"]

    data = client.get("/api/v2/overview?tz=UTC", headers=auth(key)).get_json()
    assert data["date"] == today and len(data["daily"]["steps"]) == 7 and data["daily"]["steps"][-1]["value"] == 4200
    assert data["recent_workouts"][0]["type"] == "running" and data["recent_workouts"][0]["heart_rate"]["max"] == 149
    assert {m["metric"] for m in data["measurements"]} >= {"weight", "vo2_max"}

    days = client.get(f"/api/v2/daily?metric=steps&from={today}&to={today}&tz=UTC", headers=auth(key)).get_json()
    assert days[0]["value"] == 4200
    assert client.get("/api/v2/daily?metric=nope&from=2026-01-01&to=2026-01-02", headers=auth(key)).status_code == 400
    assert client.get(f"/api/v2/sleep?from={today}&to={today}", headers=auth(key)).get_json() == []
    assert len(client.get("/api/v2/measurements", headers=auth(key)).get_json()) >= 5

    detail = client.get("/api/v2/workouts/w1?points=10", headers=auth(key)).get_json()
    assert len(detail["samples"]["heart_rate"]) == 10


def test_week_minutes_and_series_endpoints(client, session):
    import datetime
    t = session["token"]
    today = datetime.datetime.now(datetime.timezone.utc).date().isoformat()
    samples = [{"time": f"{today}T10:{m:02d}:00Z", "beatsPerMinute": 150} for m in range(30)]
    client.post("/api/v2/sync/exerciseSession", headers=auth(t), json={"data": [
        {"metadata": {"id": "w1", "dataOrigin": "p"}, "startTime": f"{today}T10:00:00Z", "endTime": f"{today}T10:30:00Z", "exerciseType": 56}]})
    client.post("/api/v2/sync/heartRate", headers=auth(t), json={"data": [
        {"metadata": {"id": "h1", "dataOrigin": "p"}, "startTime": f"{today}T10:00:00Z", "endTime": f"{today}T10:30:00Z", "samples": samples}]})
    client.post("/api/v2/sync/weight", headers=auth(t), json={"data": [
        {"metadata": {"id": "wt"}, "time": f"{today}T07:00:00Z", "weight": {"inKilograms": 78.4}}]})
    key = client.post("/api/v2/apikeys", json={"name": "k"}, headers=auth(t)).get_json()["key"]
    data = client.get("/api/v2/overview?tz=UTC&max_hr=190", headers=auth(key)).get_json()
    # 150/190 = 79% -> vigorous, counts double: 29 min of samples (last has no successor) -> 58
    assert data["week"] == {"workouts": 1, "intensity_min": 58.0}
    assert data["recent_workouts"][0]["zones_s"][2] > 0
    assert client.get("/api/v2/overview?tz=UTC", headers=auth(key)).get_json()["week"]["intensity_min"] is None
    points = client.get(f"/api/v2/series?name=weight&from={today}&to={today}&tz=UTC", headers=auth(key)).get_json()
    assert points == [{"date": today, "value": 78.4, "unit": "kg"}]
    assert client.get("/api/v2/series?name=nope&from=2026-01-01&to=2026-01-02", headers=auth(key)).status_code == 400


def test_same_session_from_two_apps_is_listed_once(client, session):
    t = session["token"]
    client.post("/api/v2/sync/exerciseSession", headers=auth(t), json={"data": [
        {"metadata": {"id": "samsung1", "dataOrigin": "samsung"}, "startTime": "2026-10-20T10:00:00Z",
         "endTime": "2026-10-20T10:31:00Z", "exerciseType": 56},
        {"metadata": {"id": "strava1", "dataOrigin": "strava"}, "startTime": "2026-10-20T10:00:30Z",
         "endTime": "2026-10-20T10:30:00Z", "exerciseType": 56, "title": "Morning run"},
        {"metadata": {"id": "later", "dataOrigin": "strava"}, "startTime": "2026-10-20T18:00:00Z",
         "endTime": "2026-10-20T18:20:00Z", "exerciseType": 56}]})
    client.post("/api/v2/sync/distance", headers=auth(t), json={"data": [
        {"metadata": {"id": "d1", "dataOrigin": "strava"}, "startTime": "2026-10-20T10:00:30Z",
         "endTime": "2026-10-20T10:30:00Z", "distance": {"value": 5, "unit": "kilometers"}}]})
    key = client.post("/api/v2/apikeys", json={"name": "k"}, headers=auth(t)).get_json()["key"]
    res = client.get("/api/v2/workouts", headers=auth(key)).get_json()
    assert sorted(w["id"] for w in res) == ["later", "strava1"]
    assert next(w for w in res if w["id"] == "strava1")["distance_m"] == 5000


def test_moving_time_pauses_best_efforts_laps_and_route(client, session):
    t = session["token"]
    base = "2026-10-20T10:"
    # 12 minutes at 3.3 m/s, stopped 60 s in the middle (no samples for a while), then moving again
    speed = [{"time": f"{base}{m:02d}:{s:02d}Z", "speed": {"inMetersPerSecond": 3.3}}
             for m in range(0, 5) for s in range(0, 60, 5)]
    speed += [{"time": f"{base}{m:02d}:{s:02d}Z", "speed": {"inMetersPerSecond": 3.3}}
              for m in range(6, 12) for s in range(0, 60, 5)]
    route = [{"time": f"{base}{m:02d}:00Z", "lat": 52.0 + i * 0.0001, "lon": 5.0, "alt": 10 + (i % 2) * 5}
             for i, m in enumerate(range(0, 12)) for _ in range(1)]
    client.post("/api/v2/sync/exerciseSession", headers=auth(t), json={"data": [
        {"metadata": {"id": "r1", "dataOrigin": "watch"}, "startTime": f"{base}00:00Z", "endTime": f"{base}12:00Z",
         "exerciseType": 56, "route": route,
         "laps": [{"startTime": f"{base}00:00Z", "endTime": f"{base}05:00Z"}],
         "segments": [{"startTime": f"{base}00:00Z", "endTime": f"{base}12:00Z", "segmentType": 46}]}]})
    client.post("/api/v2/sync/speed", headers=auth(t), json={"data": [
        {"metadata": {"id": "s1", "dataOrigin": "watch"}, "startTime": f"{base}00:00Z", "endTime": f"{base}12:00Z",
         "samples": speed}]})
    key = client.post("/api/v2/apikeys", json={"name": "k"}, headers=auth(t)).get_json()["key"]
    [w] = client.get("/api/v2/workouts", headers=auth(key)).get_json()
    assert w["paused_s"] and 55 <= w["paused_s"] <= 70
    assert w["moving_s"] < 12 * 60 - 50
    assert w["has_route"] and w["elevation"]["gain_m"] >= 0
    assert w["best_efforts"] and w["best_efforts"][0]["distance_m"] == 1000
    assert abs(w["best_efforts"][0]["seconds"] - 1000 / 3.3) < 3
    assert w["laps"][0]["seconds"] == 300 and w["laps"][0]["distance_m"] > 900
    assert w["segments"][0]["type"] == "running"
    detail = client.get("/api/v2/workouts/r1", headers=auth(key)).get_json()
    assert len(detail["route"]) == 12 and len(detail["pauses"]) == 1


def test_manual_sleep_replaces_the_same_night_from_another_app(client, session):
    t = session["token"]
    client.post("/api/v2/sync/sleepSession", headers=auth(t), json={"data": [
        {"metadata": {"id": "hc", "dataOrigin": "watch"}, "startTime": "2026-10-19T22:30:00Z", "endTime": "2026-10-20T06:30:00Z",
         "stages": [{"startTime": "2026-10-19T22:30:00Z", "endTime": "2026-10-20T06:30:00Z", "stage": 4}]},
        {"metadata": {"id": "manual-2026-10-20", "dataOrigin": "healthbridge.manual"}, "startTime": "2026-10-19T23:00:00Z",
         "endTime": "2026-10-20T07:00:00Z"},
        {"metadata": {"id": "other-night", "dataOrigin": "watch"}, "startTime": "2026-10-20T22:00:00Z", "endTime": "2026-10-21T06:00:00Z"}]})
    key = client.post("/api/v2/apikeys", json={"name": "k"}, headers=auth(t)).get_json()["key"]
    nights = client.get("/api/v2/sleep?from=2026-10-19&to=2026-10-22&tz=UTC", headers=auth(key)).get_json()
    assert [n["id"] for n in nights] == ["manual-2026-10-20", "other-night"]


def test_routes_endpoint_lists_thinned_tracks(client, session):
    t = session["token"]
    track = [{"time": f"2026-10-20T10:{m:02d}:00Z", "lat": 52 + m * 0.001, "lon": 5.0} for m in range(30)]
    client.post("/api/v2/sync/exerciseSession", headers=auth(t), json={"data": [
        {"metadata": {"id": "g1", "dataOrigin": "watch"}, "startTime": "2026-10-20T10:00:00Z", "endTime": "2026-10-20T10:30:00Z",
         "exerciseType": 56, "route": track},
        {"metadata": {"id": "g2", "dataOrigin": "watch"}, "startTime": "2026-10-21T10:00:00Z", "endTime": "2026-10-21T10:30:00Z",
         "exerciseType": 56}]})
    key = client.post("/api/v2/apikeys", json={"name": "k"}, headers=auth(t)).get_json()["key"]
    res = client.get("/api/v2/routes", headers=auth(key)).get_json()
    assert [r["id"] for r in res] == ["g1"] and len(res[0]["points"]) == 30
    assert client.get("/api/v2/routes?type=cycling", headers=auth(key)).get_json() == []
