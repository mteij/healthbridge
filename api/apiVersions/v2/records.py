import json
import logging
import os
import re

from flask import g, jsonify, request
from pymongo import ReplaceOne

import db
from blueprint import v2
from crypto import InvalidToken, data_cipher
from timeutil import canonical

log = logging.getLogger("healthbridge.records")

RECORD_TYPE = re.compile(r"^[A-Za-z]{1,40}$")
FORBIDDEN_OPERATORS = {"$where", "$function", "$accumulator", "$expr"}
MAX_FETCH = 20000


def normalize_type(method):
    """Collection names use lowerCamelCase (steps, heartRate, exerciseSession)."""
    if not RECORD_TYPE.match(method or ""):
        return None
    return method[0].lower() + method[1:]


def _has_forbidden(node):
    if isinstance(node, dict):
        return any(k in FORBIDDEN_OPERATORS or _has_forbidden(v) for k, v in node.items())
    if isinstance(node, list):
        return any(_has_forbidden(v) for v in node)
    return False


@v2.post("/sync/<method>")
def sync(method):
    record_type = normalize_type(method)
    if not record_type:
        return jsonify({"error": "invalid record type"}), 400
    body = request.get_json(silent=True) or {}
    if "data" not in body:
        return jsonify({"error": "no data provided"}), 400
    data = body["data"] if isinstance(body["data"], list) else [body["data"]]

    user = db.users().find_one({"_id": g.user})
    cipher = data_cipher(user)

    ops = []
    for index, item in enumerate(data):
        try:
            item_id = item["metadata"]["id"]
            origin = item["metadata"].get("dataOrigin")
            if "time" in item:
                start, end = canonical(item["time"]), None
            else:
                start, end = canonical(item["startTime"]), canonical(item["endTime"])
        except (KeyError, TypeError, ValueError, AttributeError):
            return jsonify({"error": "invalid record", "index": index}), 400
        payload = {k: v for k, v in item.items() if k not in ("metadata", "time", "startTime", "endTime")}
        encrypted = cipher.encrypt(json.dumps(payload).encode()).decode()
        ops.append(ReplaceOne(
            {"_id": item_id},
            {"_id": item_id, "id": item_id, "data": encrypted, "app": origin, "start": start, "end": end},
            upsert=True,
        ))

    if ops:
        db.records(g.user, record_type).bulk_write(ops, ordered=False)
    log.info("synced %d %s records", len(ops), record_type)
    return jsonify({"success": True, "count": len(ops)}), 200


def decrypt_docs(cursor, cipher):
    docs = []
    for doc in cursor:
        try:
            doc["data"] = json.loads(cipher.decrypt(doc["data"].encode()).decode())
        except InvalidToken:
            log.warning("could not decrypt record %s", doc.get("_id"))
            continue
        docs.append(doc)
    return docs


@v2.post("/fetch/<method>")
def fetch(method):
    record_type = normalize_type(method)
    if not record_type:
        return jsonify({"error": "invalid record type"}), 400
    body = request.get_json(silent=True) or {}
    queries = body.get("queries") or {}
    if not isinstance(queries, dict) or _has_forbidden(queries):
        return jsonify({"error": "invalid query"}), 400
    try:
        limit = min(int(body.get("limit", MAX_FETCH)), MAX_FETCH)
    except (TypeError, ValueError):
        return jsonify({"error": "invalid limit"}), 400

    user = db.users().find_one({"_id": g.user})
    cursor = db.records(g.user, record_type).find(queries).sort("start", 1).limit(limit)
    return jsonify(decrypt_docs(cursor, data_cipher(user))), 200


@v2.delete("/sync/<method>")
def delete_from_db(method):
    if g.get("scope") != "write":
        return jsonify({"error": "this API key is read-only"}), 403
    record_type = normalize_type(method)
    if not record_type:
        return jsonify({"error": "invalid record type"}), 400
    body = request.get_json(silent=True) or {}
    if "uuid" not in body:
        return jsonify({"error": "no uuid provided"}), 400
    uuids = body["uuid"] if isinstance(body["uuid"], list) else [body["uuid"]]
    res = db.records(g.user, record_type).delete_many({"_id": {"$in": [str(u) for u in uuids]}})
    return jsonify({"success": True, "deleted": res.deleted_count}), 200


def _fcm_notify(user, payload):
    from pyfcm import FCMNotification
    fcm = FCMNotification(service_account_file="service-account.json",
                          project_id=os.environ["FCM_PROJECT_ID"])
    fcm.notify(fcm_token=user["fcmToken"], data_payload=payload)


@v2.put("/push/<method>")
def push_data(method):
    record_type = normalize_type(method)
    if not record_type:
        return jsonify({"error": "invalid record type"}), 400
    body = request.get_json(silent=True) or {}
    if "data" not in body:
        return jsonify({"error": "no data provided"}), 400
    data = body["data"] if isinstance(body["data"], list) else [body["data"]]

    type_name = record_type[0].upper() + record_type[1:]
    for r in data:
        r["recordType"] = type_name
        if "time" not in r and ("startTime" not in r or "endTime" not in r):
            return jsonify({"error": 'no start time or end time provided. If only one time is to be used, then use the "time" attribute instead.'}), 400
        if ("startTime" in r) != ("endTime" in r):
            return jsonify({"error": "start time and end time must be provided together."}), 400

    user = db.users().find_one({"_id": g.user})
    if not user.get("fcmToken"):
        return jsonify({"error": "no fcm token found"}), 404
    try:
        _fcm_notify(user, {"op": "PUSH", "data": json.dumps(data)})
    except Exception:
        log.exception("push delivery failed")
        return jsonify({"error": "Message delivery failed"}), 500
    return jsonify({"success": True, "message": "request has been sent to device."}), 200


@v2.delete("/delete/<method>")
def delete_on_device(method):
    record_type = normalize_type(method)
    if not record_type:
        return jsonify({"error": "invalid record type"}), 400
    body = request.get_json(silent=True) or {}
    if "uuid" not in body:
        return jsonify({"error": "no uuid provided"}), 400
    uuids = body["uuid"] if isinstance(body["uuid"], list) else [body["uuid"]]

    user = db.users().find_one({"_id": g.user})
    if not user.get("fcmToken"):
        return jsonify({"error": "no fcm token found"}), 404
    try:
        _fcm_notify(user, {"op": "DEL", "data": json.dumps(
            {"uuids": uuids, "recordType": record_type[0].upper() + record_type[1:]})})
    except Exception:
        log.exception("delete delivery failed")
        return jsonify({"error": "Message delivery failed"}), 500
    return jsonify({"success": True, "message": "request has been sent to device."}), 200
