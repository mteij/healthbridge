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


def test_record_types_with_digits_are_accepted(client, session):
    t = session["token"]
    rec = {"metadata": {"id": "v"}, "time": "2026-10-20T10:00:00Z", "vo2MillilitersPerMinuteKilogram": 48.5}
    assert sync(client, t, "vo2Max", [rec]).status_code == 200
    assert sync(client, t, "Vo2Max", [rec]).status_code == 200  # old app capitalised it
    docs = client.post("/api/v2/fetch/vo2Max", json={}, headers=auth(t)).get_json()
    assert len(docs) == 1 and docs[0]["data"]["vo2MillilitersPerMinuteKilogram"] == 48.5


def test_large_payloads_are_compressed_and_roundtrip(client, session):
    import db
    t = session["token"]
    samples = [{"time": f"2026-10-20T10:{m:02d}:00Z", "beatsPerMinute": 100 + m} for m in range(60)] * 5
    rec = {"metadata": {"id": "hr"}, "startTime": "2026-10-20T10:00:00Z", "endTime": "2026-10-20T11:00:00Z", "samples": samples}
    sync(client, t, "heartRate", [rec])
    stored = db.records(db.users().find_one()["_id"], "heartRate").find_one({"_id": "hr"})
    assert stored["z"] is True and "id" not in stored
    doc = client.post("/api/v2/fetch/heartRate", json={}, headers=auth(t)).get_json()[0]
    assert doc["data"]["samples"] == samples and doc["id"] == "hr" and "z" not in doc


def test_start_index_is_created(client, session):
    import db
    sync(client, session["token"], "steps", [{"metadata": {"id": "a"}, "time": "2026-01-01T00:00:00Z", "count": 1}])
    indexes = db.records(db.users().find_one()["_id"], "steps").index_information()
    assert any(key[0][0] == "start" for key in (v["key"] for v in indexes.values()))


def test_fetch_pages_with_cursor_and_time_range(client, session):
    t = session["token"]
    items = [{"metadata": {"id": f"s{i}"}, "time": f"2026-10-20T10:0{i}:00Z", "count": i} for i in range(5)]
    sync(client, t, "steps", items)
    first = client.post("/api/v2/fetch/steps", json={"limit": 2}, headers=auth(t))
    assert [d["data"]["count"] for d in first.get_json()] == [0, 1]
    cursor = first.headers["X-Next-Cursor"]
    second = client.post("/api/v2/fetch/steps", json={"limit": 2, "cursor": cursor}, headers=auth(t))
    assert [d["data"]["count"] for d in second.get_json()] == [2, 3]
    last = client.post("/api/v2/fetch/steps", json={"limit": 2, "cursor": second.headers["X-Next-Cursor"]}, headers=auth(t))
    assert [d["data"]["count"] for d in last.get_json()] == [4] and "X-Next-Cursor" not in last.headers
    ranged = client.post("/api/v2/fetch/steps", json={"from": "2026-10-20T10:02:00Z", "to": "2026-10-20T10:03:00Z"}, headers=auth(t))
    assert [d["data"]["count"] for d in ranged.get_json()] == [2, 3]


def test_gzip_request_and_response(client, session):
    import gzip, json
    t = session["token"]
    body = gzip.compress(json.dumps({"data": [{"metadata": {"id": "z1"}, "time": "2026-10-20T10:00:00Z", "count": 3}]}).encode())
    res = client.post("/api/v2/sync/steps", data=body, headers={**auth(t), "Content-Type": "application/json", "Content-Encoding": "gzip"})
    assert res.status_code == 200 and res.get_json()["count"] == 1
    bad = client.post("/api/v2/sync/steps", data=b"not gzip", headers={**auth(t), "Content-Type": "application/json", "Content-Encoding": "gzip"})
    assert bad.status_code == 400
    many = [{"metadata": {"id": f"m{i}"}, "time": "2026-10-20T10:00:00Z", "count": i} for i in range(100)]
    sync(client, t, "steps", many)
    res = client.post("/api/v2/fetch/steps", json={}, headers={**auth(t), "Accept-Encoding": "gzip"})
    assert res.headers["Content-Encoding"] == "gzip" and len(json.loads(gzip.decompress(res.data))) == 101
