import json

import db
from conftest import auth


def rpc(client, key, method, params=None, id=1):
    return client.post("/mcp", headers=auth(key), json={"jsonrpc": "2.0", "id": id, "method": method, "params": params or {}})


def make_key(client, session):
    return client.post("/api/v2/apikeys", json={"name": "ai"}, headers=auth(session["token"])).get_json()["key"]


def seed(client, token):
    o = {"dataOrigin": "strava"}
    post = lambda kind, items: client.post(f"/api/v2/sync/{kind}", headers=auth(token), json={"data": items})
    post("exerciseSession", [{"metadata": {"id": "run1", **o}, "startTime": "2026-10-20T10:00:00Z", "endTime": "2026-10-20T10:30:00Z", "exerciseType": 56}])
    post("distance", [{"metadata": {"id": "d1", **o}, "startTime": "2026-10-20T10:00:00Z", "endTime": "2026-10-20T10:30:00Z", "distance": {"inMeters": 5000}}])
    post("steps", [
        {"metadata": {"id": "s1", "dataOrigin": "phone"}, "startTime": "2026-10-20T08:00:00Z", "endTime": "2026-10-20T09:00:00Z", "count": 1000},
        {"metadata": {"id": "s2", "dataOrigin": "watch"}, "startTime": "2026-10-20T08:00:00Z", "endTime": "2026-10-20T09:00:00Z", "count": 1200},
    ])
    post("weight", [{"metadata": {"id": "w1"}, "time": "2026-10-19T07:00:00Z", "weight": {"inKilograms": 78.2}}])
    post("sleepSession", [{"metadata": {"id": "z1"}, "startTime": "2026-10-19T21:30:00Z", "endTime": "2026-10-20T05:30:00Z",
                           "stages": [{"startTime": "2026-10-19T21:30:00Z", "endTime": "2026-10-20T01:30:00Z", "stage": 4},
                                      {"startTime": "2026-10-20T01:30:00Z", "endTime": "2026-10-20T03:30:00Z", "stage": 5},
                                      {"startTime": "2026-10-20T03:30:00Z", "endTime": "2026-10-20T04:00:00Z", "stage": 1}]}])


def call(client, key, name, args=None):
    res = rpc(client, key, "tools/call", {"name": name, "arguments": args or {}}).get_json()["result"]
    return res["isError"], json.loads(res["content"][0]["text"]) if not res["isError"] else res["content"][0]["text"]


def test_requires_api_key_and_rejects_sessions(client, session):
    assert client.post("/mcp", json={}).status_code == 401
    assert client.post("/mcp", headers=auth(session["token"]), json={}).status_code == 401


def test_initialize_and_list_tools(client, session):
    key = make_key(client, session)
    init = rpc(client, key, "initialize", {"protocolVersion": "2025-06-18"}).get_json()["result"]
    assert init["protocolVersion"] == "2025-06-18" and init["serverInfo"]["name"] == "healthbridge"
    names = {t["name"] for t in rpc(client, key, "tools/list").get_json()["result"]["tools"]}
    assert {"get_overview", "list_workouts", "get_workout", "get_daily_totals", "get_sleep"} <= names
    note = client.post("/mcp", headers=auth(key), json={"jsonrpc": "2.0", "method": "notifications/initialized"})
    assert note.status_code == 202
    assert rpc(client, key, "nope").get_json()["error"]["code"] == -32601


def test_tools_return_data(client, session):
    key = make_key(client, session)
    seed(client, session["token"])
    err, workouts = call(client, key, "list_workouts", {"type": "running", "from": "2026-10-20", "to": "2026-10-20"})
    assert not err and workouts[0]["distance_m"] == 5000
    err, detail = call(client, key, "get_workout", {"id": "run1"})
    assert detail["id"] == "run1" and "samples" in detail
    err, days = call(client, key, "get_daily_totals", {"metric": "steps", "from": "2026-10-19", "to": "2026-10-20", "timezone": "UTC"})
    assert days[1]["value"] == 1200 and days[1]["source"] == "watch"  # best single source, not 2200
    assert days[0]["value"] is None
    err, sleep = call(client, key, "get_sleep", {"from": "2026-10-20", "to": "2026-10-20", "timezone": "UTC"})
    assert sleep[0]["asleep_min"] == 360 and sleep[0]["stages_min"]["deep"] == 120
    err, latest = call(client, key, "get_latest_measurements", {"metrics": ["weight", "vo2_max"]})
    assert latest[0]["value"] == 78.2 and latest[1]["value"] is None
    err, overview = call(client, key, "get_overview")
    assert not err and "recent_workouts" in overview


def test_tool_errors_are_reported_not_raised(client, session):
    key = make_key(client, session)
    err, text = call(client, key, "get_daily_totals", {"metric": "steps", "from": "bad", "to": "2026-10-20"})
    assert err and "YYYY-MM-DD" in text
    err, text = call(client, key, "get_workout", {"id": "missing"})
    assert err


def test_landing_page(client):
    res = client.get("/")
    assert res.status_code == 200 and b"Healthbridge" in res.data and b"github.com/mteij/healthbridge" in res.data


def test_status_endpoint_reports_signups(client, session, monkeypatch):
    monkeypatch.setenv("HB_ALLOW_REGISTRATION", "true")
    monkeypatch.setenv("HB_MAX_USERS", "3")
    monkeypatch.setenv("HB_STALE_DAYS", "30")
    status = client.get("/status").get_json()
    assert status["registration"] is True and status["spots_left"] == 2 and status["inactive_days"] == 30
