from conftest import auth


def sync(client, token, kind, items):
    return client.post(f"/api/v2/sync/{kind}", json={"data": items}, headers=auth(token))


def test_sync_then_fetch_roundtrip_and_upsert(client, session):
    t = session["token"]
    rec = {"metadata": {"id": "a", "dataOrigin": "x.y"}, "startTime": "2026-10-20T10:00:00Z",
           "endTime": "2026-10-20T10:05:00Z", "count": 10}
    assert sync(client, t, "steps", [rec]).get_json()["count"] == 1
    rec["count"] = 99
    sync(client, t, "Steps", [rec])  # capitalised name is accepted, upserts same record
    docs = client.post("/api/v2/fetch/steps", json={}, headers=auth(t)).get_json()
    assert len(docs) == 1
    assert docs[0]["data"] == {"count": 99}
    assert docs[0]["start"] == "2026-10-20T10:00:00.000Z"  # canonical millisecond form


def test_time_offsets_are_normalised_to_utc(client, session):
    t = session["token"]
    rec = {"metadata": {"id": "a"}, "time": "2026-10-20T12:00:00+02:00", "x": 1}
    sync(client, t, "weight", [rec])
    doc = client.post("/api/v2/fetch/weight", json={}, headers=auth(t)).get_json()[0]
    assert doc["start"] == "2026-10-20T10:00:00.000Z"


def test_invalid_record_reports_index(client, session):
    res = sync(client, session["token"], "steps", [{"metadata": {"id": "1"}, "time": "2026-01-01T00:00:00Z"}, {"oops": 1}])
    assert res.status_code == 400 and res.get_json()["index"] == 1


def test_invalid_type_and_query_rejected(client, session):
    t = session["token"]
    assert client.post("/api/v2/fetch/..%2Fusers", json={}, headers=auth(t)).status_code in (400, 404)
    assert client.post("/api/v2/fetch/steps", json={"queries": {"$where": "1"}}, headers=auth(t)).status_code == 400
    assert client.post("/api/v2/fetch/steps", json={"queries": {"a": {"$expr": 1}}}, headers=auth(t)).status_code == 400


def test_delete_from_db(client, session):
    t = session["token"]
    sync(client, t, "steps", [{"metadata": {"id": "a"}, "time": "2026-01-01T00:00:00Z", "count": 1}])
    res = client.delete("/api/v2/sync/steps", json={"uuid": "a"}, headers=auth(t))
    assert res.get_json()["deleted"] == 1
