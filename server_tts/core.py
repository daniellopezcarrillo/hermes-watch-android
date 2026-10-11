"""Transport-free TTS delivery policy; no live synthesis, no file selection.

Wire contract (frozen):

* ``POST /challenge``  body ``{"device_id": <str>}`` (<=1024 bytes)
  -> ``{"nonce": <base64 of 16 random bytes>, "ttl": 30}``
* ``POST /audio``      body ``{"device_id", "nonce", "mac"}`` (<=2048 bytes)
  -> ``audio/mpeg`` bytes of the one fixed, pre-generated server file.

MAC (standard base64, 44 chars)::

    HMAC-SHA256(key = device_key, msg = UTF-8("hermes-watch/tts-test/v1|"
                                              + device_id + "|" + nonce))

The prefix mirrors the gateway's domain-separated legacy message
(``hermes-gadget/v1|<device>|<nonce>``): the device's enrolled 32-byte key is
the HMAC key and the prefixed UTF-8 string is the signed message. The nonce is
consumed on the first attempt, so a wrong MAC cannot be retried and a correct
MAC cannot be replayed. Comparison is constant-time.

Bounds: at most 32 live nonces total and 2 per device; each expires after 30 s.
These are memory bounds, not a usage quota. There is no API key.
"""
import base64
import hmac
import secrets
import threading
import time
from dataclasses import dataclass

MAC_PREFIX = "hermes-watch/tts-test/v1"
NONCE_TTL_S = 30
NONCE_BYTES = 16
MAX_NONCES = 32
MAX_NONCES_PER_DEVICE = 2
MAX_AUDIO_BYTES = 262144  # 256 KiB
MAX_DEVICE_ID = 64
MAX_MAC_CHARS = 44  # base64 of a 32-byte digest, with padding


@dataclass(frozen=True)
class Response:
    status: int
    body: dict = None
    audio: bytes = None


def mac_message(device, nonce):
    return ("%s|%s|%s" % (MAC_PREFIX, device, nonce)).encode("utf-8")


class TTSDelivery:
    """Challenge/verify policy over an injected registry and fixed audio blob."""

    def __init__(self, registry, *, audio, clock=time.monotonic):
        self.registry = registry
        self.audio_bytes = audio
        self.clock = clock
        self.lock = threading.RLock()
        self.nonces = {}  # nonce -> (device_id, expires_at)

    def _approved(self, device):
        """Return the device key if the registry admits it, else None.

        Evaluated on every request so a revocation or a fresh approval takes
        effect immediately. The key is never logged or returned in a body.
        """
        try:
            rec = self.registry(device)
        except Exception:
            return None
        if rec is None:
            return None
        key = getattr(rec, "key", None) if not isinstance(rec, dict) else rec.get("key")
        approved = getattr(rec, "approved", None) if not isinstance(rec, dict) else rec.get("approved")
        if approved is not True or not isinstance(key, (bytes, bytearray)) or len(key) != 32:
            return None
        return bytes(key)

    def _live_nonces(self, now):
        return {n: v for n, v in self.nonces.items() if v[1] > now}

    def challenge(self, device):
        if not isinstance(device, str) or not 1 <= len(device) <= MAX_DEVICE_ID:
            return Response(401, {"error": "unauthorized"})
        with self.lock:
            if self._approved(device) is None:
                return Response(401, {"error": "unauthorized"})
            now = self.clock()
            self.nonces = self._live_nonces(now)
            if len(self.nonces) >= MAX_NONCES:
                return Response(429, {"error": "auth_busy"})
            if sum(1 for v in self.nonces.values() if v[0] == device) >= MAX_NONCES_PER_DEVICE:
                return Response(429, {"error": "auth_busy"})
            nonce = base64.b64encode(secrets.token_bytes(NONCE_BYTES)).decode("ascii")
            self.nonces[nonce] = (device, now + NONCE_TTL_S)
            return Response(200, {"nonce": nonce, "ttl": NONCE_TTL_S})

    def audio(self, device, nonce, mac):
        if not isinstance(device, str) or not isinstance(nonce, str) or not isinstance(mac, str):
            return Response(401, {"error": "unauthorized"})
        with self.lock:
            # Consume on attempt: even a wrong MAC burns the nonce (no retry,
            # no replay). Re-check approval every request.
            item = self.nonces.pop(nonce, None)
            key = self._approved(device)
            if item is None or key is None or item[0] != device or item[1] <= self.clock():
                return Response(401, {"error": "unauthorized"})
            try:
                supplied = base64.b64decode(mac, validate=True) if len(mac) == MAX_MAC_CHARS else b""
            except (ValueError, TypeError):
                supplied = b""
            expected = hmac.digest(key, mac_message(device, nonce), "sha256")
            if not hmac.compare_digest(expected, supplied):
                return Response(401, {"error": "unauthorized"})
            return Response(200, audio=self.audio_bytes)
