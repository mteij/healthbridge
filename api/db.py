import os

_client = None
_indexed = set()


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
    _indexed.clear()


def users():
    return get_client()["hcgateway"]["users"]


def apikeys():
    return get_client()["hcgateway"]["apikeys"]


def records(userid, record_type):
    """Record collection of a user. Every query filters or sorts on `start`, so the index is
    created on first use (a no-op afterwards) instead of being a manual migration step."""
    collection = get_client()["hcgateway_" + userid][record_type]
    key = (userid, record_type)
    if key not in _indexed:
        collection.create_index("start")
        _indexed.add(key)
    return collection
