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
