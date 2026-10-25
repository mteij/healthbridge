import os

_client = None


def get_client():
    global _client
    if _client is None:
        import pymongo
        _client = pymongo.MongoClient(os.environ["MONGO_URI"])
    return _client


def set_client(client):
    """Inject a client (tests)."""
    global _client
    _client = client


def users():
    return get_client()["hcgateway"]["users"]


def apikeys():
    return get_client()["hcgateway"]["apikeys"]


def records(userid, record_type):
    return get_client()["hcgateway_" + userid][record_type]
