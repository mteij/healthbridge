import os
import sys

import pymongo
import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))
os.environ.setdefault("MONGO_URI", "mongodb://unused")

import db  # noqa: E402


@pytest.fixture()
def client():
    mongo = pymongo.MongoClient(os.environ["TEST_MONGO_URI"])
    for name in mongo.list_database_names():
        if name == "hcgateway" or name.startswith("hcgateway_"):
            mongo.drop_database(name)
    db.set_client(mongo)
    from app import create_app
    from apiVersions.v2 import auth
    auth._failures.clear()
    app = create_app()
    app.testing = True
    yield app.test_client()
    db.set_client(None)


@pytest.fixture()
def session(client):
    res = client.post("/api/v2/login", json={"username": "michiel", "password": "pw"})
    assert res.status_code == 201
    return res.get_json()


def auth(token):
    return {"Authorization": f"Bearer {token}"}
