"""Deletes accounts, and all their data, that have been inactive for too long.

Activity means any authenticated request (the phone syncing, a login, an API key being used).
Configured with environment variables; disabled unless HB_STALE_DAYS is a positive number:

    HB_STALE_DAYS      delete accounts inactive for more than this many days
    HB_STALE_EXEMPT    comma separated usernames that are never deleted
    HB_STALE_DRY_RUN   "true" to only log what would be deleted
"""
import datetime
import logging
import os
import threading

import db
from timeutil import utcnow

log = logging.getLogger("healthbridge.cleanup")
CHECK_INTERVAL_SECONDS = 6 * 3600


def _config():
    raw = os.environ.get("HB_STALE_DAYS", "")
    days = int(raw) if raw.isdigit() else 0
    exempt = {u.strip() for u in os.environ.get("HB_STALE_EXEMPT", "").split(",") if u.strip()}
    dry_run = os.environ.get("HB_STALE_DRY_RUN", "").lower() in ("1", "true", "yes")
    return days, exempt, dry_run


def delete_user(user):
    user_id = user["_id"]
    db.get_client().drop_database("hcgateway_" + user_id)
    db.apikeys().delete_many({"user": user_id})
    db.users().delete_one({"_id": user_id})


def run_cleanup(now=None, days=None, exempt=None, dry_run=None):
    """Returns the usernames that were (or, in a dry run, would be) deleted."""
    cfg_days, cfg_exempt, cfg_dry = _config()
    days = cfg_days if days is None else days
    exempt = cfg_exempt if exempt is None else exempt
    dry_run = cfg_dry if dry_run is None else dry_run
    if days <= 0:
        return []
    now = now or utcnow()
    cutoff = now - datetime.timedelta(days=days)
    removed = []
    for user in list(db.users().find()):
        seen = user.get("lastSeen")
        if seen is None:
            # accounts from before activity tracking get a fresh grace period instead of being deleted
            db.users().update_one({"_id": user["_id"]}, {"$set": {"lastSeen": now}})
            continue
        if seen >= cutoff or user["username"] in exempt:
            continue
        removed.append(user["username"])
        if dry_run:
            log.warning("dry run: would delete %s (last seen %s)", user["username"], seen)
        else:
            log.warning("deleting %s and all their data (last seen %s)", user["username"], seen)
            delete_user(user)
    return removed


def start_background_job():
    days, _, _ = _config()
    if days <= 0:
        return None

    def loop():
        stop = threading.Event()
        while not stop.wait(CHECK_INTERVAL_SECONDS if _first_done.is_set() else 60):
            _first_done.set()
            try:
                run_cleanup()
            except Exception:
                log.exception("stale data cleanup failed")

    _first_done = threading.Event()
    thread = threading.Thread(target=loop, name="stale-cleanup", daemon=True)
    thread.start()
    log.info("stale data cleanup enabled: accounts inactive for %d days are deleted", days)
    return thread
