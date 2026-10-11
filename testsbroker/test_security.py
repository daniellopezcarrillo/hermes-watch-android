import unittest

from broker.core import Broker, ProviderStub
import base64
import hashlib
import hmac
import json
import secrets


def identity(key):
    return 'hg-' + hashlib.sha256(key).hexdigest()[:16]


class AuthTests(unittest.TestCase):
    def setUp(self):
        self.now = 100.0
        self.key = secrets.token_bytes(32)
        self.device = identity(self.key)
        self.records = {self.device: dict(key=self.key, owner='owner', profile='profile',
                                         hermes_session='canonical', paired=True, revoked=False)}
        self.backend = ProviderStub()
        self.broker = Broker(self.records.get, backend=self.backend, clock=lambda: self.now)

    def authenticate(self):
        c = self.broker.challenge(self.device)
        self.assertEqual(c.status, 200)
        # Independent client-side canonicalization, not a broker signing helper.
        message = json.dumps(c.body, sort_keys=True, separators=(',', ':')).encode()
        mac = base64.b64encode(hmac.digest(self.key, message, 'sha256')).decode()
        return self.broker.authenticate(self.device, c.body['nonce'], mac)

    def test_domain_separated_one_use_challenge(self):
        c = self.broker.challenge(self.device)
        self.assertEqual(c.body['audience'], 'codex-live-broker/v1')
        message = json.dumps(c.body, sort_keys=True, separators=(',', ':')).encode()
        mac = base64.b64encode(hmac.digest(self.key, message, 'sha256')).decode()
        self.assertEqual(self.broker.authenticate(self.device, c.body['nonce'], mac).status, 200)
        self.assertEqual(self.broker.authenticate(self.device, c.body['nonce'], mac).status, 401)

    def test_wrong_hg_mac_consumes_nonce(self):
        c = self.broker.challenge(self.device)
        legacy = f"hermes-gadget/v1|{self.device}|{c.body['nonce']}".encode()
        mac = base64.b64encode(hmac.digest(self.key, legacy, 'sha256')).decode()
        self.assertEqual(self.broker.authenticate(self.device, c.body['nonce'], mac).status, 401)
        good = base64.b64encode(hmac.digest(self.key, json.dumps(c.body, sort_keys=True, separators=(',', ':')).encode(), 'sha256')).decode()
        self.assertEqual(self.broker.authenticate(self.device, c.body['nonce'], good).status, 401)

    def test_expired_challenge(self):
        c = self.broker.challenge(self.device)
        self.now += 31
        self.assertEqual(self.broker.authenticate(self.device, c.body['nonce'], '').status, 401)

    def test_revoked_unpaired_and_incorrect_identity(self):
        for field in ('revoked', 'paired'):
            self.records[self.device][field] = field == 'revoked'
            self.assertEqual(self.broker.challenge(self.device).status, 401)
            self.records[self.device][field] = field != 'revoked'
        self.records['hg-0000000000000000'] = self.records[self.device]
        self.assertEqual(self.broker.challenge('hg-0000000000000000').status, 401)

    def test_expired_start_capability_and_revocation(self):
        token = self.authenticate().body['capability']
        self.now += 60
        self.assertEqual(self.broker.start(token, 'one').status, 401)
        token = self.authenticate().body['capability']
        self.records[self.device]['revoked'] = True
        self.assertEqual(self.broker.start(token, 'two').status, 401)
        self.assertEqual(self.backend.provider_calls, 0)
        self.assertEqual(self.backend.gateway_calls, 0)


class LeaseTests(AuthTests):
    def setUp(self):
        super().setUp()
        from broker.core import Admission
        self.admission = Admission(2)
        self.broker = Broker(self.records.get, backend=self.backend, clock=lambda: self.now,
                             admission=self.admission)
        self.addCleanup(self.broker.shutdown)

    def reserve(self):
        token = self.authenticate().body['capability']
        result = self.broker.start(token, secrets.token_hex(8))
        self.assertEqual(result.status, 201)
        return result.body

    def test_fresh_capability_same_request_never_new_session(self):
        token = self.authenticate().body['capability']
        first = self.broker.start(token, 'recovered')
        self.broker.stop(first.body['session_capability'], first.body['session_id'])
        fresh = self.authenticate().body['capability']
        self.assertEqual(self.broker.start(fresh, 'recovered'), first)
        self.assertEqual(self.admission.active_count, 0)

    def test_active_absolute_deadline(self):
        first = self.reserve()
        self.broker.offer(first['session_capability'], first['session_id'], SDP)
        self.now += 120
        self.broker.tick()
        self.assertTrue(self.broker.wait_cleanup(1))
        self.assertEqual(self.admission.active_count, 0)
        self.assertEqual(self.broker.events(first['session_capability'], first['session_id'], b'{}').status, 401)

    def test_reservation_replay_and_scope(self):
        token = self.authenticate().body['capability']
        first = self.broker.start(token, 'same')
        self.assertEqual(first.status, 201)
        self.assertEqual(self.broker.start(token, 'same'), first)
        self.assertEqual(self.broker.start(token, 'different').status, 401)
        self.assertEqual(self.broker.start(first.body['session_capability'], 'new').status, 401)
        self.assertEqual(self.backend.provider_calls, 0)
        self.assertNotIn('key', first.body)

    def test_owner_only_idempotent_stop(self):
        first = self.reserve()
        key2 = secrets.token_bytes(32)
        dev2 = identity(key2)
        self.records[dev2] = dict(self.records[self.device], key=key2, hermes_session='second-canonical')
        self.device, self.key = dev2, key2
        second = self.reserve()
        self.assertEqual(self.broker.stop(second['session_capability'], first['session_id']).status, 403)
        self.assertEqual(self.broker.offer(second['session_capability'], first['session_id'], SDP).status, 403)
        self.assertEqual(self.broker.events(second['session_capability'], first['session_id'], b'{}').status, 403)
        self.assertEqual(self.broker.stop(first['session_capability'], first['session_id']).body['state'], 'CLOSED')
        self.assertEqual(self.broker.stop(first['session_capability'], first['session_id']).body['state'], 'CLOSED')

    def test_process_global_capacity_and_device_lease(self):
        first = self.reserve()
        token = self.authenticate().body['capability']
        self.assertEqual(self.broker.start(token, 'busy').status, 409)
        other = Broker(self.records.get, backend=ProviderStub(), admission=self.admission, clock=lambda: self.now)
        self.addCleanup(other.shutdown)
        c = other.challenge(self.device)
        mac = base64.b64encode(hmac.digest(self.key, json.dumps(c.body, sort_keys=True, separators=(',', ':')).encode(), 'sha256')).decode()
        token2 = other.authenticate(self.device, c.body['nonce'], mac).body['capability']
        self.assertEqual(other.start(token2, 'other-busy').status, 409)
        self.broker.stop(first['session_capability'], first['session_id'])
        self.assertEqual(other.start(token2, 'after-stop').status, 201)

    def test_reservation_deadline_releases_quota(self):
        first = self.reserve()
        self.now += 15
        self.broker.tick()
        self.assertEqual(self.broker.stop(first['session_capability'], first['session_id']).body['state'], 'CLOSED')
        self.reserve()

    def test_offer_failure_releases_only_after_retirement(self):
        first = self.reserve()
        self.backend.fail = True
        self.backend.retired = False
        self.assertEqual(self.broker.offer(first['session_capability'], first['session_id'], SDP).status, 502)
        token = self.authenticate().body['capability']
        self.assertEqual(self.broker.start(token, 'still-held').status, 409)
        self.now += 5
        self.broker.tick()
        self.assertTrue(self.broker.wait_cleanup(1))
        self.assertGreater(self.backend.force_calls, 0)
        self.assertEqual(self.broker.start(token, 'not-retired').status, 409)
        self.backend.retired = True
        self.broker.tick()
        self.assertTrue(self.broker.wait_cleanup(1))
        self.now += 60
        token = self.authenticate().body['capability']
        self.assertEqual(self.broker.start(token, 'retired').status, 201)

    def test_active_deadline_and_revocation_cleanup(self):
        first = self.reserve()
        self.assertEqual(self.broker.offer(first['session_capability'], first['session_id'], SDP).status, 200)
        self.assertEqual(self.broker.offer(first['session_capability'], first['session_id'], SDP).status, 409)
        self.records[self.device]['revoked'] = True
        self.assertEqual(self.broker.events(first['session_capability'], first['session_id'], b'{}').status, 401)
        self.broker.tick()
        self.assertTrue(self.broker.wait_cleanup(1))
        self.assertEqual(self.admission.active_count, 0)

    def test_stop_during_negotiation_holds_worker_lease(self):
        import threading
        first = self.reserve()
        entered, release = threading.Event(), threading.Event()
        original = self.backend.negotiate
        def blocked(*args):
            entered.set()
            release.wait(2)
            return original(*args)
        self.backend.negotiate = blocked
        results = []
        worker = threading.Thread(target=lambda: results.append(self.broker.offer(first['session_capability'], first['session_id'], SDP)))
        worker.start()
        self.assertTrue(entered.wait(1))
        stopped = self.broker.stop(first['session_capability'], first['session_id'])
        self.assertEqual(stopped.body['state'], 'STOPPING')
        self.assertEqual(self.admission.active_count, 1)
        release.set()
        worker.join(2)
        self.assertFalse(worker.is_alive())
        self.assertEqual(results[0].status, 502)
        self.assertTrue(self.broker.wait_cleanup(1))
        self.assertEqual(self.admission.active_count, 0)

    def test_wrong_generation_and_oversized_answer_cleanup(self):
        original = self.backend.negotiate
        for field, value in (('generation', 'wrong'), ('sdp', SDP + 'x' * 65536)):
            first = self.reserve()
            def invalid(*args):
                result = original(*args)
                result[field] = value
                return result
            self.backend.negotiate = invalid
            self.assertEqual(self.broker.offer(first['session_capability'], first['session_id'], SDP).status, 502)
            self.assertTrue(self.broker.wait_cleanup(1))
            self.assertEqual(self.admission.active_count, 0)

    def test_rate_limits(self):
        for i in range(3):
            first = self.reserve()
            self.broker.stop(first['session_capability'], first['session_id'])
        token = self.authenticate().body['capability']
        self.assertEqual(self.broker.start(token, 'limited').status, 429)
        self.now += 60
        self.reserve()

    def test_sdp_bounds_and_events_are_fail_closed(self):
        first = self.reserve()
        token, sid = first['session_capability'], first['session_id']
        for bad in (SDP + 'x' * 65536, SDP + 'm=video 9 RTP/AVP 96\r\n', 'invalid'):
            self.assertEqual(self.broker.offer(token, sid, bad).status, 400)
        self.assertEqual(self.backend.provider_calls, 0)
        self.assertEqual(self.broker.offer(token, sid, SDP).status, 200)
        for bad in (b'x' * 16385, b'{"type":"tool.execute"}', b'{"type":"status", "x": [[[[[[[[[0]]]]]]]]]}', b'\xff'):
            self.assertEqual(self.broker.events(token, sid, bad).status, 400)
        self.assertEqual(self.backend.gateway_calls, 0)
        for i in range(32):
            self.assertEqual(self.broker.events(token, sid, b'{"type":"status"}').status, 202)
        self.assertEqual(self.broker.events(token, sid, b'{"type":"status"}').status, 429)


SDP = 'v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n'


class BoundsTests(unittest.TestCase):
    def test_stream_bounds_compression_depth_duplicates(self):
        from broker.core import bounded_json
        self.assertEqual(bounded_json([b'{"ok":', b'true}']), {'ok': True})
        for chunks, options in (([b'x' * 98305], {}), ([b'{}'], {'encoding': 'gzip'}),
                                 ([b'[[[[[[[[[0]]]]]]]]]'], {}), ([b'\xff'], {}),
                                 ([b'{"a":1,"a":2}'], {}), ([b'{"a":NaN}'], {})):
            with self.assertRaises(ValueError):
                bounded_json(chunks, **options)

    def test_process_admission_atomic_maximum(self):
        import concurrent.futures
        from broker.core import Admission
        gate = Admission(1)
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            result = list(pool.map(lambda n: gate.acquire((str(n), 'owner', 'p', str(n)), str(n)), range(32)))
        self.assertEqual(sum(result), 1)
        self.assertEqual(gate.active_count, 1)


class SecurityTests(unittest.TestCase):
    def test_unauthorized_start_has_zero_upstream_calls(self):
        backend = ProviderStub()
        broker = Broker(lambda device: None, backend=backend)
        result = broker.start(None, 'request')
        self.assertEqual(result.status, 401)
        self.assertEqual(backend.provider_calls, 0)
        self.assertEqual(backend.gateway_calls, 0)


if __name__ == '__main__':
    unittest.main()
