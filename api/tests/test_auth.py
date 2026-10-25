import db
from conftest import auth


def test_login_creates_first_user_then_closes_registration(client, monkeypatch):
    assert client.post("/api/v2/login", json={"username": "a", "password": "x"}).status_code == 201
    monkeypatch.delenv("HB_ALLOW_REGISTRATION", raising=False)
    assert client.post("/api/v2/login", json={"username": "b", "password": "x"}).status_code == 403


def test_registration_can_be_enabled(client, monkeypatch, session):
    monkeypatch.setenv("HB_ALLOW_REGISTRATION", "true")
    assert client.post("/api/v2/login", json={"username": "b", "password": "x"}).status_code == 201


def test_wrong_password_rejected_and_rate_limited(client, session):
    for _ in range(10):
        assert client.post("/api/v2/login", json={"username": "michiel", "password": "bad"}).status_code == 403
    assert client.post("/api/v2/login", json={"username": "michiel", "password": "pw"}).status_code == 429


def test_login_does_not_rotate_refresh_token(client, session):
    # expire the session, as happens when the phone has been idle for 12h
    import datetime
    db.users().update_one({}, {"$set": {"expiry": datetime.datetime(2000, 1, 1)}})
    again = client.post("/api/v2/login", json={"username": "michiel", "password": "pw"}).get_json()
    assert again["token"] != session["token"]
    assert again["refresh"] == session["refresh"]
    refreshed = client.post("/api/v2/refresh", json={"refresh": session["refresh"]})
    assert refreshed.status_code == 200


def test_requests_without_valid_token_fail(client, session):
    assert client.post("/api/v2/fetch/steps", json={}).status_code == 401
    assert client.post("/api/v2/fetch/steps", json={}, headers={"Authorization": "Bearer"}).status_code == 401
    assert client.post("/api/v2/fetch/steps", json={}, headers=auth("nope")).status_code == 403


def test_api_key_is_read_only(client, session):
    created = client.post("/api/v2/apikeys", json={"name": "openclaw"}, headers=auth(session["token"]))
    key = created.get_json()["key"]
    assert key.startswith("hb_")
    assert client.post("/api/v2/fetch/steps", json={}, headers=auth(key)).status_code == 200
    assert client.get("/api/v2/workouts", headers=auth(key)).status_code == 200
    item = {"metadata": {"id": "1"}, "time": "2026-01-01T00:00:00Z", "count": 1}
    assert client.post("/api/v2/sync/steps", json={"data": [item]}, headers=auth(key)).status_code == 403
    assert client.post("/api/v2/apikeys", json={}, headers=auth(key)).status_code == 403
    # key is stored hashed
    assert db.apikeys().find_one({})["hash"] != key
    listed = client.get("/api/v2/apikeys", headers=auth(session["token"])).get_json()
    assert listed[0]["name"] == "openclaw" and "key" not in listed[0]
    assert client.delete(f"/api/v2/apikeys/{listed[0]['id']}", headers=auth(session["token"])).status_code == 200
    assert client.get("/api/v2/workouts", headers=auth(key)).status_code == 403


def test_user_cap_limits_open_registration(client, monkeypatch, session):
    monkeypatch.setenv("HB_ALLOW_REGISTRATION", "true")
    monkeypatch.setenv("HB_MAX_USERS", "2")
    assert client.post("/api/v2/login", json={"username": "b", "password": "x"}).status_code == 201
    assert client.post("/api/v2/login", json={"username": "c", "password": "x"}).status_code == 403
