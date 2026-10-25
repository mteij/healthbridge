"""Re-encrypt stored records with the key derived from HB_DATA_KEY.

The original implementation derives its encryption key from a constant prefix of the password
hash, so all data shares one effectively public key. Set HB_DATA_KEY to a long random
string (the server then writes new records with it and still reads old ones), then run
this once to convert existing records:

    docker compose exec api python /app/scripts/reencrypt.py

Safe to re-run; records already using the new key are left alone. Take a backup first.
"""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "api"))

import db  # noqa: E402
from crypto import _configured_fernet, _legacy_fernet, InvalidToken  # noqa: E402

new = _configured_fernet()
if new is None:
    sys.exit("HB_DATA_KEY is not set")

client = db.get_client()
total = converted = 0
for user in db.users().find():
    legacy = _legacy_fernet(user)
    database = client["hcgateway_" + user["_id"]]
    for name in database.list_collection_names():
        for doc in database[name].find({}, {"data": 1}):
            total += 1
            try:
                new.decrypt(doc["data"].encode())
                continue
            except InvalidToken:
                pass
            plain = legacy.decrypt(doc["data"].encode())
            database[name].update_one({"_id": doc["_id"]}, {"$set": {"data": new.encrypt(plain).decode()}})
            converted += 1
print(f"{converted} of {total} records converted")
