"""Approval registry adapters for the TTS delivery test service.

A device is admitted only when **both** facts hold, re-read on every request:

1. it has an enrolled record (a 32-byte device key) in the dedicated gateway's
   ``devices.json`` (``gateway/store.py`` ``DeviceStore.FILENAME``); and
2. the real Hermes ``PairingStore`` considers it *approved* —
   ``gateway.pairing.PairingStore.is_approved("gadget", device_id)``. That is the
   grant written only by ``hermes gadget pair`` (``approve_code("gadget", code)``)
   into the pairing store's ``gadget-approved.json`` and removed by
   ``hermes pairing revoke gadget <device_id>``.

These are **two independent stores** and must not be conflated. A device's
``pending_until`` marker in ``devices.json`` only controls how long its
enrollment record is kept (what ``DeviceStore.confirm``/``forget`` act on); it
is *not* the security approval. A device can therefore be enrolled and confirmed
(no ``pending_until``) yet still be denied, because approval lives in the pairing
store — and a *pending* device (marker present) can already be approved there,
because ``hermes gadget pair`` writes the pairing store, not ``confirm()``.

This module reproduces both reads without importing the gateway at runtime, and
offers an *optional* adapter that reuses the real ``gateway.store.DeviceStore``
when the Hermes venv sources are on ``sys.path``.

Device keys are read only to verify the request HMAC. They are never returned
to a caller, written to a response or logged.
"""
import base64
import json
import os
import time
from dataclasses import dataclass
from pathlib import Path

DEVICES_FILENAME = "devices.json"
#: PairingStore platform under which ``hermes gadget`` records approvals.
PAIRING_PLATFORM = "gadget"
APPROVED_SUFFIX = "-approved.json"
LEGACY_PAIRING_DIR = "pairing"
PAIRING_SUBDIR = ("platforms", "pairing")

#: Marker sentinel: a ``pending_until`` key is present but not a usable number.
_PENDING_MALFORMED = object()


@dataclass(frozen=True)
class Record:
    """An admitted device: its verification key and approval state."""
    key: bytes
    approved: bool


def _decode_key(value):
    if not isinstance(value, str):
        return None
    try:
        key = base64.b64decode(value, validate=True)
    except (ValueError, TypeError):
        return None
    return key if len(key) == 32 else None


def _pending_marker(rec):
    """Return the record's pending marker as a float, ``None``, or the malformed sentinel.

    ``None`` means the record carries no ``pending_until`` key. A present marker
    is returned as-is so the caller can compare it to the clock. A present but
    non-numeric marker (including a JSON boolean — ``isinstance(True, int)`` in
    Python would otherwise coerce it) is reported as malformed so the caller
    fails closed instead of silently treating the device as approved.
    """
    if "pending_until" not in rec:
        return None
    until = rec["pending_until"]
    if isinstance(until, bool) or not isinstance(until, (int, float)):
        return _PENDING_MALFORMED
    return until


def default_pairing_dir(home=None):
    """The pairing directory the ``hermes`` CLI uses for the running root.

    Mirrors ``gateway.pairing.PAIRING_DIR`` and
    ``hermes_constants.get_hermes_dir("platforms/pairing", "pairing")``: the
    legacy ``<root>/pairing`` wins only when it actually holds files (an empty
    stub must not shadow the consolidated location), otherwise the consolidated
    ``<root>/platforms/pairing`` is used. ``<root>`` is ``HERMES_HOME`` when set
    (Docker / custom deployments, e.g. ``/opt/data``) and ``~/.hermes``
    otherwise — the same root the CLI resolves at runtime.
    """
    root = Path(home) if home is not None else Path(
        os.environ.get("HERMES_HOME") or (Path.home() / ".hermes"))
    legacy = root / LEGACY_PAIRING_DIR
    try:
        if legacy.is_dir() and next(legacy.iterdir(), None) is not None:
            return legacy
    except OSError:
        pass
    return root.joinpath(*PAIRING_SUBDIR)


class PairingApprovalReader:
    """Required approval source: the real PairingStore ``is_approved`` check.

    Reads the pairing store's ``gadget-approved.json`` directly — the exact file
    ``PairingStore._approved_path("gadget")`` writes and ``is_approved("gadget",
    device_id)`` consults. A device id is approved only when it is present as an
    owned entry; a missing, unreadable or malformed file fails closed (not
    approved). Re-read on every call, so ``hermes pairing revoke gadget
    <device_id>`` takes effect on the next request without a restart.
    """

    def __init__(self, directory):
        self._path = Path(directory) / (PAIRING_PLATFORM + APPROVED_SUFFIX)

    @property
    def path(self):
        return self._path

    def is_approved(self, device_id):
        if not isinstance(device_id, str) or not device_id:
            return False
        try:
            approved = json.loads(self._path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return False
        return isinstance(approved, dict) and isinstance(approved.get(device_id), dict)


class DeviceStoreRegistry:
    """Read ``devices.json`` for enrollment and the pairing store for approval.

    Both reads happen on every call (no caching): an operator approving or
    revoking a device must take effect on the next request without restarting
    the service. The enrollment key comes from ``devices.json``; approval is
    delegated, invariantly, to a required :class:`PairingApprovalReader`. An
    expired ``pending_until`` record is treated as absent (mirroring
    ``gateway/store.py`` ``_expired``), and a malformed marker denies.
    """

    def __init__(self, directory, *, approvals):
        if not callable(getattr(approvals, "is_approved", None)):
            raise ValueError("approval reader with is_approved required")
        self._path = Path(directory) / DEVICES_FILENAME
        self._approvals = approvals

    @property
    def path(self):
        return self._path

    def lookup(self, device_id):
        if not isinstance(device_id, str) or not device_id:
            return None
        try:
            raw = json.loads(self._path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return None
        if not isinstance(raw, dict):
            return None
        devices = raw.get("devices")
        rec = devices.get(device_id) if isinstance(devices, dict) else None
        if not isinstance(rec, dict):
            return None
        marker = _pending_marker(rec)
        if marker is _PENDING_MALFORMED:
            return None  # malformed enrollment marker: fail closed
        if marker is not None and marker < time.time():
            return None  # expired pending enrollment == no record
        key = _decode_key(rec.get("key"))
        if key is None:
            return None
        return Record(key=key, approved=self._approvals.is_approved(device_id))


class GatewayStoreRegistry:
    """Optional adapter over the real ``gateway.store.DeviceStore``.

    Only usable on a gateway host where the pinned plugin's ``gateway`` package
    is importable (``sys.path`` includes the Hermes venv sources). Enrollment
    (the decoded 32-byte key) comes from ``DeviceStore.key_for``; approval comes
    from the required :class:`PairingApprovalReader`, exactly as in
    :class:`DeviceStoreRegistry`. The store's own ``pending`` flag is only a
    strict-boolean sanity check — a malformed (numeric or string) value denies
    rather than being coerced into an approval.
    """

    def __init__(self, store, *, approvals):
        if not callable(getattr(approvals, "is_approved", None)):
            raise ValueError("approval reader with is_approved required")
        self._store = store
        self._approvals = approvals

    @classmethod
    def open(cls, directory, *, pairing_dir=None):
        from gateway.store import DeviceStore  # optional: gateway host only
        approvals = PairingApprovalReader(
            pairing_dir if pairing_dir is not None else default_pairing_dir())
        return cls(DeviceStore(directory), approvals=approvals)

    def lookup(self, device_id):
        if not isinstance(device_id, str) or not device_id:
            return None
        try:
            info = self._store.devices().get(device_id)
            key = self._store.key_for(device_id)
        except Exception:
            return None
        if not isinstance(info, dict) or not isinstance(key, (bytes, bytearray)):
            return None
        if len(key) != 32:
            return None
        pending = info.get("pending")
        if pending is not None and pending is not False:
            # A present, non-``False`` flag (True, 0, 1, "false", ...) is not a
            # well-formed approval signal: deny instead of coercing it.
            return None
        return Record(key=bytes(key), approved=self._approvals.is_approved(device_id))


class FixtureRegistry:
    """Explicit in-memory registry for tests and offline dry runs.

    ``records`` maps ``device_id`` to ``{"key": <32 bytes>, "approved": bool}``.
    Approval requires the strict boolean ``True``; any other value denies.
    """

    def __init__(self, records=None):
        self._records = dict(records or {})

    def lookup(self, device_id):
        rec = self._records.get(device_id)
        if not isinstance(rec, dict):
            return None
        key = rec.get("key")
        if not isinstance(key, (bytes, bytearray)) or len(key) != 32:
            return None
        return Record(key=bytes(key), approved=rec.get("approved") is True)
