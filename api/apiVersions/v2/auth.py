import datetime
import hashlib
import logging
import os
import secrets
import time

from argon2 import PasswordHasher
from bson.objectid import ObjectId
from flask import g, jsonify, request

import db
from blueprint import v2
from timeutil import utcnow

log = logging.getLogger("healthbridge.auth")
ph = PasswordHasher()

SESSION_HOURS = 12
API_KEY_PREFIX = "hb_"
# endpoints a read-only API key may call
READ_ONLY_ENDPOINTS = {"v2.fetch", "v2.workouts", "v2.workout_detail"}
PUBLIC_ENDPOINTS = {"v2.login", "v2.refresh"}

MAX_FAILURES = 10
LOCKOUT_SECONDS = 300
_failures = {}


def _locked(username):
    count, since = _failures.get(username, (0, 0))
    if count >= MAX_FAILURES and time.monotonic() - since < LOCKOUT_SECONDS:
        return True
    if count >= MAX_FAILURES:
        _failures.pop(username, None)
    return False


def _record_failure(username):
    count, _ = _failures.get(username, (0, 0))
    _failures[username] = (count + 1, time.monotonic())


def hash_key(key):
    return hashlib.sha256(key.encode("utf-8")).hexdigest()


def _new_session(user_id, keep_refresh=None):
    token = secrets.token_urlsafe(32)
    refresh = keep_refresh or secrets.token_urlsafe(32)
    expiry = utcnow() + datetime.timedelta(hours=SESSION_HOURS)
    db.users().update_one(
        {"_id": user_id}, {"$set": {"token": token, "refresh": refresh, "expiry": expiry}}
    )
    return {"token": token, "refresh": refresh, "expiry": expiry.isoformat()}


def _bearer():
    header = request.headers.get("Authorization", "")
    parts = header.split(" ", 1)
    if len(parts) != 2 or parts[0].lower() != "bearer" or not parts[1].strip():
        return None
    return parts[1].strip()


@v2.before_request
def authenticate():
    if request.endpoint in PUBLIC_ENDPOINTS or request.method == "OPTIONS":
        return None

    token = _bearer()
    if token is None:
        return jsonify({"error": "no token provided"}), 401

    g.scope = "write"
    if token.startswith(API_KEY_PREFIX):
        key = db.apikeys().find_one({"hash": hash_key(token)})
        if not key:
            return jsonify({"error": "invalid token"}), 403
        if request.endpoint not in READ_ONLY_ENDPOINTS:
            return jsonify({"error": "this API key is read-only"}), 403
        db.apikeys().update_one({"_id": key["_id"]}, {"$set": {"lastUsed": utcnow()}})
        g.user = key["user"]
        g.scope = "read"
        g.auth_kind = "apikey"
        return None

    user = db.users().find_one({"token": token})
    if not user:
        return jsonify({"error": "invalid token"}), 403
    if utcnow() > user["expiry"]:
        return jsonify({"error": "token expired. Use /api/v2/login to reauthenticate."}), 403
    g.user = user["_id"]
    g.auth_kind = "session"
    return None


def _registration_allowed():
    count = db.users().count_documents({})
    if count == 0:
        return True
    if os.environ.get("HB_ALLOW_REGISTRATION", "").lower() not in ("1", "true", "yes"):
        return False
    limit = os.environ.get("HB_MAX_USERS")
    return not (limit and limit.isdigit() and count >= int(limit))


@v2.post("/login")
def login():
    body = request.get_json(silent=True) or {}
    username, password = body.get("username"), body.get("password")
    fcm_token = body.get("fcmToken")
    if not isinstance(username, str) or not isinstance(password, str) or not username or not password:
        return jsonify({"error": "invalid request"}), 400
    if _locked(username):
        return jsonify({"error": "too many failed attempts, try again later"}), 429

    user = db.users().find_one({"username": username})
    if not user:
        if not _registration_allowed():
            _record_failure(username)
            return jsonify({"error": "registration is disabled"}), 403
        user_id = str(ObjectId())
        db.users().insert_one({"_id": user_id, "username": username, "password": ph.hash(password)})
        log.info("registered user %s", username)
        session = _new_session(user_id)
        if fcm_token:
            db.users().update_one({"_id": user_id}, {"$set": {"fcmToken": fcm_token}})
        return jsonify(session), 201

    try:
        ph.verify(user["password"], password)
    except Exception:
        _record_failure(username)
        return jsonify({"error": "invalid password"}), 403
    _failures.pop(username, None)

    if fcm_token:
        db.users().update_one({"_id": user["_id"]}, {"$set": {"fcmToken": fcm_token}})

    if "token" not in user or "expiry" not in user or utcnow() > user["expiry"]:
        # The refresh token is deliberately NOT rotated here: another client logging
        # in (a script, the gateway reader) must not invalidate the phone's session.
        session = _new_session(user["_id"], keep_refresh=user.get("refresh"))
    else:
        session = {
            "token": user["token"],
            "refresh": user["refresh"],
            "expiry": user["expiry"].isoformat(),
        }
    return jsonify(session), 201


@v2.post("/refresh")
def refresh():
    body = request.get_json(silent=True) or {}
    refresh_token = body.get("refresh")
    if not isinstance(refresh_token, str) or not refresh_token:
        return jsonify({"error": "no refresh token provided"}), 400
    user = db.users().find_one({"refresh": refresh_token})
    if not user:
        return jsonify({"error": "invalid refresh token"}), 403
    return jsonify(_new_session(user["_id"], keep_refresh=refresh_token)), 200


@v2.delete("/revoke")
def revoke():
    body = request.get_json(silent=True) or {}
    refresh_token = body.get("refresh")
    if not isinstance(refresh_token, str) or not refresh_token:
        return jsonify({"error": "no refresh token provided"}), 400
    user = db.users().find_one({"refresh": refresh_token})
    if not user or user["_id"] != g.user:
        return jsonify({"error": "invalid refresh token"}), 403
    db.users().update_one({"_id": user["_id"]}, {"$unset": {"token": 1, "refresh": 1, "expiry": 1}})
    return jsonify({"success": True}), 200


# ---- API keys ---------------------------------------------------------------

@v2.post("/apikeys")
def create_apikey():
    if g.get("auth_kind") != "session":
        return jsonify({"error": "API keys can only be created with a login session"}), 403
    body = request.get_json(silent=True) or {}
    name = str(body.get("name") or "unnamed")[:60]
    key = API_KEY_PREFIX + secrets.token_urlsafe(32)
    doc = {"user": g.user, "name": name, "hash": hash_key(key), "scope": "read",
           "created": utcnow(), "lastUsed": None}
    key_id = str(db.apikeys().insert_one(doc).inserted_id)
    return jsonify({"id": key_id, "name": name, "scope": "read", "key": key,
                    "note": "Store this key now; it cannot be shown again."}), 201


@v2.get("/apikeys")
def list_apikeys():
    if g.get("auth_kind") != "session":
        return jsonify({"error": "API keys can only be managed with a login session"}), 403
    out = []
    for k in db.apikeys().find({"user": g.user}):
        out.append({"id": str(k["_id"]), "name": k["name"], "scope": k["scope"],
                    "created": k["created"].isoformat(),
                    "lastUsed": k["lastUsed"].isoformat() if k.get("lastUsed") else None})
    return jsonify(out), 200


@v2.delete("/apikeys/<key_id>")
def delete_apikey(key_id):
    if g.get("auth_kind") != "session":
        return jsonify({"error": "API keys can only be managed with a login session"}), 403
    try:
        oid = ObjectId(key_id)
    except Exception:
        return jsonify({"error": "invalid key id"}), 400
    res = db.apikeys().delete_one({"_id": oid, "user": g.user})
    if not res.deleted_count:
        return jsonify({"error": "key not found"}), 404
    return jsonify({"success": True}), 200
