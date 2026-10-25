"""Undo HB_ROLLUP_DAYS for periods the app has re-imported.

Roll-up replaces minute records by hourly totals. After you have switched it off and let the app
re-import the period (Settings, "Re-import everything"), the minute records are back and the hourly
totals would be counted on top of them. This removes every hourly total whose hour has minute
records again. Safe to re-run; totals for hours that have not been re-imported are kept.

    docker run --rm --network <stack>_default --env-file api.env -v ./hcgateway-src:/src:ro \
      <image> python /src/scripts/unroll.py [--dry-run]
"""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "api"))

import db  # noqa: E402
from timeutil import parse_iso, to_iso  # noqa: E402
from rollup import ROLLUP_TYPES, HOUR  # noqa: E402

dry_run = "--dry-run" in sys.argv
removed = kept = 0
for user in db.users().find():
    for record_type in ROLLUP_TYPES:
        collection = db.records(user["_id"], record_type)
        for bucket in list(collection.find({"agg": True}, {"app": 1, "start": 1})):
            start = parse_iso(bucket["start"])
            has_raw = collection.count_documents({
                "agg": {"$ne": True}, "app": bucket.get("app"),
                "start": {"$gte": to_iso(start), "$lt": to_iso(start + HOUR)},
            }, limit=1)
            if has_raw:
                removed += 1
                if not dry_run:
                    collection.delete_one({"_id": bucket["_id"]})
            else:
                kept += 1
print(f"{'would remove' if dry_run else 'removed'} {removed} hourly totals, kept {kept} without minute records")
