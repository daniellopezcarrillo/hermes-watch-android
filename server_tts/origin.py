"""Derive the private HTTPS audio origin from the watch's saved WSS endpoint.

Frozen contract: the audio delivery origin shares the *hostname* the watch
already saved for its ``wss://`` gateway endpoint and uses HTTPS port **8445**.
The existing gadget endpoint on ``8444`` is unchanged; this service never
mutates the saved watch endpoint or the gadget port.
"""
from urllib.parse import urlsplit

AUDIO_HTTPS_PORT = 8445
GADGET_PORT = 8444


def audio_origin(wss_url):
    """Return ``https://<saved-host>:8445`` for a validated ``wss://`` URL.

    Accepts only the shape the watch's own endpoint validator admits: scheme
    ``wss``, a host, no user-info, query or fragment. Raises ``ValueError``
    otherwise so a caller can fail closed instead of building an origin from a
    malformed or attacker-supplied string.
    """
    if not isinstance(wss_url, str) or not wss_url or len(wss_url) > 2048:
        raise ValueError("invalid endpoint")
    parts = urlsplit(wss_url)
    if parts.scheme != "wss" or not parts.hostname or parts.username or parts.password:
        raise ValueError("invalid endpoint")
    if parts.query or parts.fragment:
        raise ValueError("query and fragment are not allowed")
    host = parts.hostname.lower()
    if ":" in host:  # IPv6 literal
        host = "[%s]" % host
    return "https://%s:%d" % (host, AUDIO_HTTPS_PORT)
