"""Independent client-side helpers for the TTS delivery tests.

Nothing here imports the service's own MAC helper: the client signature is
recomputed from the frozen contract so a server bug cannot pass its own test.
"""
import base64
import hashlib
import hmac

# A tiny but structurally valid MP3: MPEG-1 Layer III frame sync (0xFF 0xFB)
# followed by a deterministic payload. The service never decodes it.
MP3_FIXTURE = b'\xff\xfb\x90\x00' + bytes(range(256)) * 8

MAC_PREFIX = 'hermes-watch/tts-test/v1'


def device_id(key):
    return 'hw-' + hashlib.sha256(key).hexdigest()[:16]


def mac(key, device, nonce):
    message = ('%s|%s|%s' % (MAC_PREFIX, device, nonce)).encode('utf-8')
    return base64.b64encode(hmac.digest(key, message, 'sha256')).decode('ascii')


def records(pairs):
    """Build FixtureRegistry records from ``(device, key, approved)`` triples."""
    return {device: {'key': key, 'approved': approved} for device, key, approved in pairs}
