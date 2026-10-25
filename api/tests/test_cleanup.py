import datetime

import db
from cleanup import run_cleanup
from conftest import auth

NOW = datetime.datetime(2026, 11, 1, 12, 0, 0)


def add_user(name, last_seen):
    doc = {"_id": "id-" + name, "username": name, "password": "x"}
    if last_seen is not None:
        doc["lastSeen"] = last_seen
    db.users().insert_one(doc)
    db.records("id-" + name, "steps").insert_one({"_id": "r", "data": "x", "start": "2026-01-01T00:00:00.000Z", "end": None})
    db.apikeys().insert_one({"user": "id-" + name, "name": "k", "hash": "h" + name})


def exists(name):
    return db.users().count_documents({"username": name}) == 1


def test_deletes_inactive_users_with_all_their_data(client):
    add_user("stale", NOW - datetime.timedelta(days=31))
    add_user("fresh", NOW - datetime.timedelta(days=29))
    assert run_cleanup(now=NOW, days=30, exempt=set(), dry_run=False) == ["stale"]
    assert not exists("stale") and exists("fresh")
    assert "hcgateway_id-stale" not in db.get_client().list_database_names()
    assert db.apikeys().count_documents({"user": "id-stale"}) == 0
    assert db.records("id-fresh", "steps").count_documents({}) == 1


def test_exempt_dry_run_and_disabled(client):
    add_user("owner", NOW - datetime.timedelta(days=400))
    add_user("old", NOW - datetime.timedelta(days=400))
    assert run_cleanup(now=NOW, days=30, exempt={"owner"}, dry_run=True) == ["old"]
    assert exists("old")  # dry run keeps everything
    assert run_cleanup(now=NOW, days=0, exempt=set(), dry_run=False) == []
    assert run_cleanup(now=NOW, days=30, exempt={"owner"}, dry_run=False) == ["old"]
    assert exists("owner") and not exists("old")


def test_accounts_without_activity_marker_get_a_grace_period(client):
    add_user("legacy", None)
    assert run_cleanup(now=NOW, days=30, exempt=set(), dry_run=False) == []
    assert db.users().find_one({"username": "legacy"})["lastSeen"] == NOW


def test_activity_updates_last_seen(client, session):
    db.users().update_one({}, {"$set": {"lastSeen": datetime.datetime(2000, 1, 1)}})
    assert client.post("/api/v2/fetch/steps", json={}, headers=auth(session["token"])).status_code == 200
    assert db.users().find_one({})["lastSeen"] > datetime.datetime(2020, 1, 1)
