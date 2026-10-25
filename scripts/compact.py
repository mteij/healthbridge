"""One-off storage compaction for data written before the 0.3 server.

- drops the redundant `id` field (it duplicates `_id`)
- deflates large payloads (heart rate and speed samples) before encryption
- makes sure the `start` index exists

Safe to re-run. Every rewritten record is decoded again and compared with the original before it is
saved. Run `--dry-run` first to see what would change:

    docker compose run --rm -v ./scripts:/scripts api python /scripts/compact.py --dry-run
"""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "api"))
if os.path.isdir("/app"):
    sys.path.insert(0, "/app")

import db  # noqa: E402
from crypto import COMPRESS_MIN_BYTES, data_cipher, decode_payload, encode_payload  # noqa: E402

dry_run = "--dry-run" in sys.argv
client = db.get_client()
changed = saved = checked = 0
for user in db.users().find():
    cipher = data_cipher(user)
    database = client["hcgateway_" + user["_id"]]
    for name in database.list_collection_names():
        if name.startswith("system."):
            continue
        collection = db.records(user["_id"], name)  # creates the index
        if not dry_run:
            collection.update_many({"id": {"$exists": True}}, {"$unset": {"id": ""}})
        for doc in collection.find({"z": {"$ne": True}}, {"data": 1, "z": 1}):
            checked += 1
            if len(doc["data"]) < COMPRESS_MIN_BYTES:
                continue
            payload = decode_payload(cipher, doc)
            token, compressed = encode_payload(cipher, payload)
            if not compressed or len(token) >= len(doc["data"]):
                continue
            assert decode_payload(cipher, {"data": token, "z": True}) == payload
            saved += len(doc["data"]) - len(token)
            changed += 1
            if not dry_run:
                collection.update_one({"_id": doc["_id"]}, {"$set": {"data": token, "z": True}})
print(f"{'would compress' if dry_run else 'compressed'} {changed} of {checked} records, saving {saved / 1024:.0f} KB")
