import datetime

UTC = datetime.timezone.utc


def parse_iso(value):
    """Parse an ISO-8601 timestamp (with Z or an offset) into an aware UTC datetime."""
    if isinstance(value, datetime.datetime):
        dt = value
    else:
        text = str(value).strip()
        if text.endswith(("Z", "z")):
            text = text[:-1] + "+00:00"
        dt = datetime.datetime.fromisoformat(text)
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=UTC)
    return dt.astimezone(UTC)


def to_iso(dt):
    """Canonical form stored in the database: UTC, millisecond precision, trailing Z.

    A fixed-width format keeps lexicographic order equal to chronological order,
    which the range queries rely on.
    """
    dt = dt.astimezone(UTC)
    return dt.strftime("%Y-%m-%dT%H:%M:%S.") + f"{dt.microsecond // 1000:03d}Z"


def canonical(value):
    return None if value is None else to_iso(parse_iso(value))


def utcnow():
    """Naive UTC, matching how expiry timestamps are stored in MongoDB."""
    return datetime.datetime.now(UTC).replace(tzinfo=None)
