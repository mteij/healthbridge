import base64
import hashlib
import os

from cryptography.fernet import Fernet, InvalidToken, MultiFernet


def _legacy_fernet(user):
    # Upstream derives the key from the first 32 characters of the argon2 hash,
    # which is only the (constant) parameter prefix. Kept so existing data stays
    # readable; set HB_DATA_KEY and run scripts/reencrypt.py to move away from it.
    key = base64.urlsafe_b64encode(user["password"].encode("utf-8").ljust(32)[:32])
    return Fernet(key)


def _configured_fernet():
    secret = os.environ.get("HB_DATA_KEY")
    if not secret:
        return None
    key = base64.urlsafe_b64encode(hashlib.sha256(secret.encode("utf-8")).digest())
    return Fernet(key)


def data_cipher(user):
    """Cipher used for reading and writing record payloads of `user`.

    The first key encrypts; all keys are tried when decrypting.
    """
    legacy = _legacy_fernet(user)
    configured = _configured_fernet()
    if configured is None:
        return MultiFernet([legacy])
    return MultiFernet([configured, legacy])


__all__ = ["data_cipher", "InvalidToken"]
