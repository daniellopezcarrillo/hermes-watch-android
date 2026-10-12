"""Transport-free broker policy; no provider or gateway integration."""
from dataclasses import dataclass


@dataclass(frozen=True)
class Response:
    status: int
    body: dict


class ProviderStub:
    """Explicit offline stub, never a live subscription backend."""
    label = 'offline-provider-stub'

    def __init__(self):
        self.provider_calls = 0
        self.gateway_calls = 0
        self.fail = False
        self.retired = True
        self.force_calls = 0

    def negotiate(self, session, generation, sdp, deadline):
        self.provider_calls += 1
        if self.fail:
            raise RuntimeError('offline failure')
        return dict(session_id=session, generation=generation, sdp=sdp)

    def stop(self, session):
        return self.retired

    def force_stop(self, session):
        self.force_calls += 1
        return self.retired


import base64
import hashlib
import hmac
import json
import math
import secrets
import threading
import time


def device_id(key):
    if not isinstance(key, bytes) or len(key) != 32:
        raise ValueError('invalid device key')
    return 'hg-' + hashlib.sha256(key).hexdigest()[:16]


def integral_expiry(now, seconds):
    """Integral monotonic epoch seconds for every emitted ``expires_at``.

    The broker clock is a monotonic float; a float expiry serialized as JSON
    (``expires_at``: ``123486.789``) cannot round-trip through a strict client
    integer parse and would silently change the signed canonical message.
    Flooring ``now`` is deliberately fail-closed: the advertised integer expiry
    never outlives the intended monotonic deadline, so sub-second rounding can
    never extend a nonce or capability lifetime, and the value is a stable,
    non-decreasing function of the clock.
    """
    return math.floor(now) + seconds


class Admission:
    """Share one instance across every broker in a process; default capacity is one."""
    def __init__(self, maximum=1):
        if type(maximum) is not int or maximum < 1:
            raise ValueError('invalid capacity')
        self.maximum = maximum
        self.lock = threading.RLock()
        self.leases = {}

    @property
    def active_count(self):
        with self.lock:
            return len(self.leases)

    def acquire(self, principal, lease):
        with self.lock:
            if (len(self.leases) >= self.maximum
                    or any(p[0] == principal[0] or p[2:] == principal[2:]
                           for p in self.leases.values())):
                return False
            self.leases[lease] = principal
            return True

    def release(self, lease):
        with self.lock:
            self.leases.pop(lease, None)


PROCESS_ADMISSION = Admission()

# Independent process-wide lanes: a stuck ordinary stop cannot starve escalation.
# Permits cover publication through reconciliation; there is no executor queue.
CLEANUP_MAXIMUM = 4
CLEANUP_PERMITS = {
    False: threading.BoundedSemaphore(CLEANUP_MAXIMUM),
    True: threading.BoundedSemaphore(CLEANUP_MAXIMUM),
}

from contextlib import contextmanager


class Broker:
    def __init__(self, registry, *, backend, clock=time.monotonic, admission=None):
        self.registry = registry
        self.backend = backend
        self.clock = clock
        self.lock = threading.RLock()
        self.nonces = {}
        self.caps = {}
        self.admission = admission if admission is not None else PROCESS_ADMISSION
        self.sessions = {}
        self.replays = {}
        self.rates = {}
        self.requests = {}
        self._shutdown = False
        self._force_cleanup_cursor = None
        self._cleanup_actions = []
        self._cleanup_workers = set()
        self._cleanup_changed = threading.Condition(self.lock)

    @contextmanager
    def _cleanup_scope(self):
        # Retirement decisions and worker reservations are atomic with policy.
        # Thread publication and every backend call occur after releasing it.
        actions = []
        try:
            with self.lock:
                try:
                    yield
                finally:
                    actions, self._cleanup_actions = self._cleanup_actions, []
        finally:
            for action in actions:
                sid, session, generation, force = action
                thread = None
                try:
                    thread = threading.Thread(target=self._cleanup, args=action,
                                              name='broker-force' if force else 'broker-stop',
                                              daemon=True)
                    with self.lock:
                        self._cleanup_workers.add(thread)
                    thread.start()
                except Exception:
                    with self.lock:
                        self._cleanup_workers.discard(thread)
                        session['cleanup_running'][force] = False
                        CLEANUP_PERMITS[force].release()
                        self._cleanup_changed.notify_all()

    def wait_cleanup(self, timeout=0):
        """Bounded host/test drain; never waits for a backend under policy lock."""
        with self._cleanup_changed:
            return self._cleanup_changed.wait_for(
                lambda: not self._cleanup_workers and not any(
                    any(s.get('cleanup_running', {}).values())
                    for s in self.sessions.values()), timeout)

    def _cleanup(self, sid, session, generation, force):
        retired = False
        try:
            retired = (self.backend.force_stop(sid) if force else self.backend.stop(sid)) is True
        except Exception:
            pass
        finally:
            with self.lock:
                # A stale callback may not mutate or release a replacement lease.
                if self.sessions.get(sid) is session and session['generation'] == generation:
                    session['cleanup_running'][force] = False
                    session['retired'] = session.get('retired', False) or retired
                    self._close_retired(sid, session)
                self._cleanup_workers.discard(threading.current_thread())
                CLEANUP_PERMITS[force].release()
                self._cleanup_changed.notify_all()

    def _close_retired(self, sid, session):
        if (self.sessions.get(sid) is session and session['state'] == 'STOPPING'
                and session.get('retired', not session['allocated'])
                and not session['worker']
                and not any(session.get('cleanup_running', {}).values())):
            session.update(state='CLOSED', events=[], event_bytes=0)
            self.admission.release(sid)

    def _approved(self, device):
        try:
            record = self.registry(device)
            if (not record or record.get('paired') is not True or record.get('revoked') is not False
                    or device_id(record.get('key')) != device):
                return None
            if not all(isinstance(record.get(k), str) and 0 < len(record[k]) <= 128
                       for k in ('owner', 'profile', 'hermes_session')):
                return None
            return (device, record['owner'], record['profile'], record['hermes_session'])
        except Exception:
            return None

    def challenge(self, device):
        with self.lock:
            if self._shutdown:
                return Response(503, {'error': 'shutdown'})
            principal = self._approved(device)
            if not principal:
                return Response(401, {'error': 'unauthorized'})
            now = self.clock()
            self.nonces = {n: v for n, v in self.nonces.items() if v[0]['expires_at'] > now}
            if len(self.nonces) >= 1024:
                return Response(429, {'error': 'auth_busy'})
            if not self._rate(('auth', device), 10):
                return Response(429, {'error': 'auth_rate_limited'})
            nonce = secrets.token_urlsafe(32)
            body = dict(version=1, audience='codex-live-broker/v1', device_id=device,
                        nonce=nonce, expires_at=integral_expiry(now, 30), scope='live:start',
                        owner=principal[1], profile=principal[2], hermes_session=principal[3])
            self.nonces[nonce] = (body, principal)
            return Response(200, dict(body))

    def authenticate(self, device, nonce, mac):
        with self.lock:
            if self._shutdown:
                return Response(503, {'error': 'shutdown'})
            now = self.clock()
            item = self.nonces.pop(nonce, None) if isinstance(nonce, str) else None
            principal = self._approved(device)
            if not item or not principal or item[1] != principal or item[0]['expires_at'] <= now:
                return Response(401, {'error': 'unauthorized'})
            try:
                supplied = base64.b64decode(mac, validate=True) if isinstance(mac, str) and len(mac) == 44 else b''
                message = json.dumps(item[0], sort_keys=True, separators=(',', ':')).encode()
                expected = hmac.digest(self.registry(device)['key'], message, 'sha256')
            except Exception:
                return Response(401, {'error': 'unauthorized'})
            if not hmac.compare_digest(expected, supplied):
                return Response(401, {'error': 'unauthorized'})
            token = self._cap(principal, 'live:start', None, now + 60)
            return Response(200, dict(capability=token, expires_at=integral_expiry(now, 60)))

    def _rate(self, key, maximum):
        now = self.clock()
        recent = [t for t in self.rates.get(key, []) if t > now - 60]
        if len(recent) >= maximum:
            return False
        self.rates[key] = recent + [now]
        return True

    def _cap(self, principal, scope, session, expiry):
        token = secrets.token_urlsafe(32)
        self.caps[hashlib.sha256(token.encode()).digest()] = (principal, scope, session, expiry)
        return token

    def _authorize(self, token, scope, session=None):
        if not isinstance(token, str) or len(token) != 43:
            return None
        cap = self.caps.get(hashlib.sha256(token.encode()).digest())
        if (not cap or cap[1] != scope or cap[2] != session or cap[3] <= self.clock()
                or self._approved(cap[0][0]) != cap[0]):
            return None
        return cap[0]

    def start(self, token, request_id):
        with self.lock:
            if self._shutdown:
                return Response(503, {'error': 'shutdown'})
            principal = self._authorize(token, 'live:start')
            if not principal:
                return Response(401, {'error': 'unauthorized'})
            if not isinstance(request_id, str) or not 1 <= len(request_id) <= 128:
                return Response(400, {'error': 'invalid_request'})
            digest = hashlib.sha256(token.encode()).digest()
            replay = self.replays.get(digest)
            if replay:
                if replay[0] != request_id:
                    return Response(401, {'error': 'consumed'})
                return Response(201, dict(replay[1]))
            request_key = (principal[0], request_id)
            prior = self.requests.get(request_key)
            if prior:
                if prior[0] != principal:
                    return Response(403, {'error': 'forbidden'})
                self.replays[digest] = (request_id, dict(prior[1]))
                return Response(201, dict(prior[1]))
            if not self._rate(('start', principal[0]), 3):
                return Response(429, {'error': 'start_rate_limited'})
            sid = secrets.token_urlsafe(24)
            if not self.admission.acquire(principal, sid):
                return Response(409, {'error': 'busy'})
            now = self.clock()
            expiry = integral_expiry(now, 120)
            cap = self._cap(principal, 'live:session', sid, now + 120)
            self.sessions[sid] = dict(principal=principal, state='RESERVED', expiry=expiry,
                                     deadline=now + 15, generation=secrets.token_hex(16),
                                     allocated=False, worker=False, events=[], event_bytes=0)
            body = dict(session_id=sid, expires_at=expiry, session_capability=cap)
            self.replays[digest] = (request_id, dict(body))
            self.requests[request_key] = (principal, dict(body))
            return Response(201, body)

    def _session(self, token, sid):
        # Authenticate token before ownership comparison, avoiding existence disclosure.
        if not isinstance(token, str) or len(token) != 43:
            return None, Response(401, {'error': 'unauthorized'})
        cap = self.caps.get(hashlib.sha256(token.encode()).digest())
        if (not cap or cap[1] != 'live:session' or cap[3] <= self.clock()
                or self._approved(cap[0][0]) != cap[0]):
            return None, Response(401, {'error': 'unauthorized'})
        if cap[2] != sid:
            return None, Response(403, {'error': 'forbidden'})
        session = self.sessions.get(sid)
        if not session or session['principal'] != cap[0]:
            return None, Response(403, {'error': 'forbidden'})
        return session, None

    @staticmethod
    def _sdp(sdp):
        if not isinstance(sdp, str) or len(sdp) > 65536:
            return False
        try:
            if len(sdp.encode('utf-8')) > 65536:
                return False
        except UnicodeError:
            return False
        lines = sdp.split('\r\n')
        media = [line.split() for line in lines if line.startswith('m=')]
        return (sdp.startswith('v=0\r\n') and all(x in sdp for x in ('\r\no=', '\r\ns=', '\r\nt='))
                and len(media) == 2 and media[0][0] == 'm=audio'
                and media[1][0] == 'm=application' and all(len(m) >= 4 for m in media)
                and 'webrtc-datachannel' in media[1])

    def offer(self, token, sid, sdp):
        with self._cleanup_scope():
            if self._shutdown:
                return Response(503, {'error': 'shutdown'})
            session, error = self._session(token, sid)
            if error:
                return error
            if session['state'] != 'RESERVED':
                return Response(409, {'error': 'invalid_state'})
            if self.clock() >= session['deadline']:
                self._retire(sid, session)
                return Response(410, {'error': 'expired'})
            if not self._sdp(sdp):
                return Response(400, {'error': 'invalid_sdp'})
            session.update(state='NEGOTIATING', deadline=min(session['expiry'], self.clock() + 20), allocated=True, worker=True)
            generation, deadline = session['generation'], session['deadline']
        # Backend must enforce deadline and incremental RPC bounds. No lock during I/O.
        try:
            answer = self.backend.negotiate(sid, generation, sdp, deadline)
        except Exception:
            with self._cleanup_scope():
                if self.sessions.get(sid) is not session or session['generation'] != generation:
                    return Response(502, {'error': 'backend_failure'})
                session['worker'] = False
                self._retire(sid, session)
            return Response(502, {'error': 'backend_failure'})
        with self._cleanup_scope():
            if self.sessions.get(sid) is not session or session['generation'] != generation:
                return Response(502, {'error': 'invalid_backend_answer'})
            session['worker'] = False
            if (session['state'] != 'NEGOTIATING' or self.clock() >= deadline
                    or self._approved(session['principal'][0]) != session['principal']
                    or not isinstance(answer, dict) or answer.get('session_id') != sid
                    or answer.get('generation') != generation or not self._sdp(answer.get('sdp'))):
                self._retire(sid, session)
                return Response(502, {'error': 'invalid_backend_answer'})
            session.update(state='ACTIVE', deadline=session['expiry'])
            return Response(200, dict(session_id=sid, type='answer', sdp=answer['sdp'], expires_at=session['expiry']))

    def _retire(self, sid, session, force=False):
        """Called under _cleanup_scope: reserve only, never perform backend I/O."""
        if self.sessions.get(sid) is not session or session['state'] == 'CLOSED':
            return
        if session['state'] != 'STOPPING':
            session.update(state='STOPPING', deadline=self.clock() + 5)
        self._close_retired(sid, session)
        if session['state'] == 'CLOSED' or not session['allocated']:
            return
        running = session.setdefault('cleanup_running', {False: False, True: False})
        if (not session.get('retired', False) and not running[force]
                and CLEANUP_PERMITS[force].acquire(blocking=False)):
            running[force] = True
            self._cleanup_actions.append((sid, session, session['generation'], force))
            if force:
                self._force_cleanup_cursor = sid

    def stop(self, token, sid):
        with self._cleanup_scope():
            session, error = self._session(token, sid)
            if error:
                return error
            self._retire(sid, session)
            return Response(200, dict(session_id=sid, state=session['state']))

    def tick(self):
        """Host schedules policy expiry; bounded workers perform cleanup off-lock."""
        with self._cleanup_scope():
            now = self.clock()
            # Resume after the last admitted force retry, not insertion order.
            # Only successful reservations advance the cursor; no work is queued.
            sessions = list(self.sessions.items())
            for index, (sid, _) in enumerate(sessions):
                if sid == self._force_cleanup_cursor:
                    sessions = sessions[index + 1:] + sessions[:index + 1]
                    break
            for sid, session in sessions:
                if session['state'] == 'CLOSED':
                    if now >= session['expiry']:
                        del self.sessions[sid]
                    continue
                if (session['state'] == 'STOPPING' or now >= session['deadline']
                        or self._approved(session['principal'][0]) != session['principal']):
                    self._retire(sid, session, force=session['state'] == 'STOPPING' and now >= session['deadline'])
            self.requests = {k: v for k, v in self.requests.items() if v[1]['expires_at'] > now}
            self.rates = {k: [t for t in v if t > now - 60] for k, v in self.rates.items()
                          if any(t > now - 60 for t in v)}
            self.caps = {k: v for k, v in self.caps.items() if v[3] > now}
            self.replays = {k: v for k, v in self.replays.items() if k in self.caps}
            self.nonces = {k: v for k, v in self.nonces.items() if v[0]['expires_at'] > now}

    def shutdown(self):
        with self._cleanup_scope():
            self._shutdown = True
            for sid, session in self.sessions.items():
                self._retire(sid, session, force=True)

    def events(self, token, sid, payload):
        with self.lock:
            session, error = self._session(token, sid)
            if error:
                return error
            if session['state'] != 'ACTIVE' or self.clock() >= session['expiry']:
                return Response(409, {'error': 'invalid_state'})
            try:
                event = bounded_json([payload], maximum=16384)
                if not isinstance(event, dict) or event != {'type': 'status'}:
                    raise ValueError('unsupported event')
            except (ValueError, TypeError):
                return Response(400, {'error': 'invalid_event'})
            if len(session['events']) >= 32 or session['event_bytes'] + len(payload) > 131072:
                return Response(429, {'error': 'event_queue_full'})
            session['events'].append(payload)
            session['event_bytes'] += len(payload)
            return Response(202, {'state': 'queued'})


def bounded_json(chunks, *, maximum=98304, encoding='identity'):
    """Bound bytes before decoding/parsing; reject compressed and deep JSON."""
    if encoding != 'identity':
        raise ValueError('compression unsupported')
    data = bytearray()
    for chunk in chunks:
        if not isinstance(chunk, bytes) or len(chunk) > maximum - len(data):
            raise ValueError('body too large')
        data.extend(chunk)
    text = data.decode('utf-8')
    depth, quoted, escaped = 0, False, False
    for char in text:
        if quoted:
            if escaped:
                escaped = False
            elif char == '\\':
                escaped = True
            elif char == '"':
                quoted = False
        elif char == '"':
            quoted = True
        elif char in '{[':
            depth += 1
            if depth > 8:
                raise ValueError('too deep')
        elif char in '}]':
            depth -= 1
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError('duplicate key')
            result[key] = value
        return result
    return json.loads(text, object_pairs_hook=unique,
                      parse_constant=lambda value: (_ for _ in ()).throw(ValueError('invalid constant')))
