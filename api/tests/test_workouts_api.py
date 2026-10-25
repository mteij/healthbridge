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
