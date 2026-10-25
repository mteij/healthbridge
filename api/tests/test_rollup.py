import datetime

import db
from conftest import auth
from rollup import bucket_id, rollup_user
from timeutil import parse_iso

NOW = datetime.datetime(2026, 11, 1, 12, 30, tzinfo=datetime.timezone.utc)


def post(client, token, kind, items):
    return client.post(f"/api/v2/sync/{kind}", headers=auth(token), json={"data": items})


def minute_steps(day, app, n):
    return [{"metadata": {"id": f"{app}-{day}-{i}", "dataOrigin": app}, "startTime": f"2026-10-{day:02d}T08:{i:02d}:00Z",
             "endTime": f"2026-10-{day:02d}T08:{i:02d}:59Z", "count": 10} for i in range(n)]


def user():
    return db.users().find_one()


def test_small_old_records_become_hourly_totals(client, session, monkeypatch):
    monkeypatch.setenv("HB_ROLLUP_DAYS", "14")
    t = session["token"]
    post(client, t, "steps", minute_steps(10, "phone", 30) + minute_steps(10, "watch", 20) + minute_steps(30, "phone", 5))
    # one longer record (a workout's own steps) must stay raw
    post(client, t, "steps", [{"metadata": {"id": "long", "dataOrigin": "watch"}, "startTime": "2026-10-10T09:00:00Z",
                              "endTime": "2026-10-10T09:45:00Z", "count": 5000}])
    removed, written = rollup_user(user(), now=NOW)
    assert (removed, written) == (50, 2)
    docs = {d["_id"]: d for d in client.post("/api/v2/fetch/steps", json={}, headers=auth(t)).get_json()}
    hour = parse_iso("2026-10-10T08:00:00Z")
    assert docs[bucket_id("steps", "phone", hour)]["data"] == {"count": 300}
    assert docs[bucket_id("steps", "watch", hour)]["data"] == {"count": 200}
    assert "long" in docs and docs["long"]["data"]["count"] == 5000
    assert sum(1 for d in docs.values() if d["_id"].startswith("phone-10-")) == 0
    assert sum(1 for d in docs.values() if d["_id"].startswith("phone-30-")) == 5  # newer than the cutoff


def test_resyncing_a_rolled_up_hour_does_not_double_count(client, session, monkeypatch):
    monkeypatch.setenv("HB_ROLLUP_DAYS", "14")
    t = session["token"]
    post(client, t, "steps", minute_steps(10, "phone", 30))
    rollup_user(user(), now=NOW)
    post(client, t, "steps", minute_steps(10, "phone", 30))  # app re-imports the same period
    docs = client.post("/api/v2/fetch/steps", json={}, headers=auth(t)).get_json()
    assert len(docs) == 1 and docs[0]["data"] == {"count": 300}


def test_distance_and_energy_totals_use_canonical_units(client, session, monkeypatch):
    monkeypatch.setenv("HB_ROLLUP_DAYS", "14")
    t = session["token"]
    post(client, t, "distance", [{"metadata": {"id": f"d{i}", "dataOrigin": "p"}, "startTime": f"2026-10-10T08:0{i}:00Z",
                                 "endTime": f"2026-10-10T08:0{i}:59Z", "distance": {"inMeters": 100, "inKilometers": 0.1}} for i in range(3)])
    rollup_user(user(), now=NOW)
    [doc] = client.post("/api/v2/fetch/distance", json={}, headers=auth(t)).get_json()
    assert doc["data"] == {"distance": {"inMeters": 300.0}} and doc["start"] == "2026-10-10T08:00:00.000Z" and doc["end"] == "2026-10-10T09:00:00.000Z"


def test_disabled_by_default(client, session, monkeypatch):
    monkeypatch.delenv("HB_ROLLUP_DAYS", raising=False)
    post(client, session["token"], "steps", minute_steps(10, "phone", 5))
    assert rollup_user(user(), now=NOW) == (0, 0)


def test_hours_with_a_workout_are_left_exact(client, session, monkeypatch):
    monkeypatch.setenv("HB_ROLLUP_DAYS", "14")
    t = session["token"]
    post(client, t, "steps", minute_steps(10, "phone", 30))  # 08:00-08:30 on the 10th
    post(client, t, "exerciseSession", [{"metadata": {"id": "run", "dataOrigin": "phone"}, "startTime": "2026-10-10T08:10:00Z",
                                         "endTime": "2026-10-10T08:40:00Z", "exerciseType": 56}])
    post(client, t, "steps", minute_steps(12, "phone", 30))  # same hour on a day without a workout
    assert rollup_user(user(), now=NOW) == (30, 1)
    ids = {d["_id"] for d in client.post("/api/v2/fetch/steps", json={}, headers=auth(t)).get_json()}
    assert sum(i.startswith("phone-10-") for i in ids) == 30  # kept
    assert sum(i.startswith("phone-12-") for i in ids) == 0 and any(i.startswith("agg:steps") for i in ids)
