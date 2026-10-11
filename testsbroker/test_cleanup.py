"""Deterministic offline cleanup/negotiation concurrency regressions."""
import threading
import unittest

from broker.core import Admission, Broker, ProviderStub
from testsbroker.test_security import SDP, identity
import secrets


class CleanupTests(unittest.TestCase):
    def setUp(self):
        self.now = 100.0
        key = secrets.token_bytes(32)
        self.device = identity(key)
        self.records = {self.device: dict(key=key, owner='o', profile='p',
                                        hermes_session='h', paired=True, revoked=False)}
        self.backend = ProviderStub()
        self.gate = Admission()
        self.broker = Broker(self.records.get, backend=self.backend,
                             admission=self.gate, clock=lambda: self.now)
        # Capabilities issued under the lock to isolate cleanup from auth signing.
        with self.broker.lock:
            token = self.broker._cap(self.broker._approved(self.device), 'live:start', None, 160)
        self.body = self.broker.start(token, 'first').body
        self.sid = self.body['session_id']
        self.cap = self.body['session_capability']
        self.threads = []
        self.releases = []
        self.addCleanup(self.finish)

    def finish(self):
        for event in self.releases:
            event.set()
        for thread in self.threads:
            thread.join(2)
        self.broker.shutdown()
        if hasattr(self.broker, 'wait_cleanup'):
            self.broker.wait_cleanup(2)

    def spawn(self, fn):
        done = threading.Event()
        results = []
        def run():
            try:
                results.append(fn())
            finally:
                done.set()
        thread = threading.Thread(target=run, daemon=True)
        self.threads.append(thread)
        thread.start()
        return done, results

    def blocker(self, name, result=True):
        entered, release = threading.Event(), threading.Event()
        self.releases.append(release)
        def blocked(*args):
            entered.set()
            release.wait()
            return result
        setattr(self.backend, name, blocked)
        return entered, release

    def test_process_global_lane_caps_across_brokers(self):
        from broker.core import CLEANUP_MAXIMUM
        brokers, releases, entered = [], [], []
        for index in range(CLEANUP_MAXIMUM + 1):
            backend = ProviderStub()
            broker = Broker(self.records.get, backend=backend, admission=Admission(),
                            clock=lambda: self.now)
            with broker.lock:
                token = broker._cap(broker._approved(self.device), 'live:start', None, 160)
            body = broker.start(token, str(index)).body
            broker.offer(body['session_capability'], body['session_id'], SDP)
            stop_entered, stop_release = threading.Event(), threading.Event()
            force_entered, force_release = threading.Event(), threading.Event()
            def blocked(event, release):
                def call(sid):
                    event.set()
                    release.wait()
                    return True
                return call
            backend.stop = blocked(stop_entered, stop_release)
            backend.force_stop = blocked(force_entered, force_release)
            brokers.append((broker, body))
            releases.extend((stop_release, force_release))
            entered.append((stop_entered, force_entered))
        try:
            for broker, body in brokers:
                broker.stop(body['session_capability'], body['session_id'])
            self.now += 5
            for broker, _ in brokers:
                broker.tick()
            for events in entered[:-1]:
                for event in events:
                    self.assertTrue(event.wait(1))
            self.assertFalse(any(event.is_set() for event in entered[-1]))
            self.assertEqual(sum(len(b._cleanup_workers) for b, _ in brokers), 2 * CLEANUP_MAXIMUM)
            # No queued/spawned workers on saturation; retained session retries on tick.
            for release in releases[:-2]:
                release.set()
            for broker, _ in brokers[:-1]:
                self.assertTrue(broker.wait_cleanup(1))
            brokers[-1][0].tick()
            self.assertTrue(entered[-1][1].wait(1))
        finally:
            for release in releases:
                release.set()
            for broker, _ in brokers:
                broker.wait_cleanup(2)
                broker.shutdown()
                broker.wait_cleanup(2)

    def test_shutdown_and_failed_offer_do_not_block_auth(self):
        for fail in (False, True):
            with self.subTest(fail=fail):
                self.backend.fail = fail
                entered, release = self.blocker('stop' if fail else 'force_stop')
                if fail:
                    done, results = self.spawn(lambda: self.broker.offer(self.cap, self.sid, SDP))
                else:
                    self.broker.offer(self.cap, self.sid, SDP)
                    done, results = self.spawn(self.broker.shutdown)
                self.assertTrue(entered.wait(1))
                self.assertTrue(done.wait(.5))
                challenge_done, _ = self.spawn(lambda: self.broker.challenge(self.device))
                self.assertTrue(challenge_done.wait(.5))
                self.assertEqual(self.gate.active_count, 1)
                release.set()
                self.assertTrue(self.broker.wait_cleanup(1))
                self.assertEqual(self.gate.active_count, 0)
                if not fail:
                    self.broker = Broker(self.records.get, backend=self.backend,
                                         admission=self.gate, clock=lambda: self.now)
                    with self.broker.lock:
                        token = self.broker._cap(self.broker._approved(self.device), 'live:start', None, 160)
                    self.body = self.broker.start(token, 'second').body
                    self.sid, self.cap = self.body['session_id'], self.body['session_capability']

    def test_shutdown_latches_admission_and_negotiation(self):
        import base64
        import hmac
        import json
        challenge = self.broker.challenge(self.device)
        key = self.records[self.device]['key']
        mac = base64.b64encode(hmac.digest(key, json.dumps(
            challenge.body, sort_keys=True, separators=(',', ':')).encode(), 'sha256')).decode()
        with self.broker.lock:
            token = self.broker._cap(self.broker._approved(self.device), 'live:start', None, 160)
        self.broker.shutdown()
        self.broker.shutdown()
        for response in (
                self.broker.challenge(self.device),
                self.broker.authenticate(self.device, challenge.body['nonce'], mac),
                self.broker.start(token, 'after-shutdown'),
                self.broker.offer(self.cap, self.sid, SDP)):
            self.assertEqual(response.status, 503)
            self.assertEqual(response.body, {'error': 'shutdown'})
        self.assertEqual(self.broker.stop(self.cap, self.sid).status, 200)
        self.broker.tick()
        self.assertEqual(self.gate.active_count, 0)
        self.assertEqual(self.backend.provider_calls, 0)
        self.assertEqual(self.backend.gateway_calls, 0)

    def test_shutdown_during_negotiation_holds_lease_until_worker_retires(self):
        entered, release = threading.Event(), threading.Event()
        self.releases.append(release)
        original = self.backend.negotiate
        def negotiate(*args):
            entered.set()
            release.wait()
            return original(*args)
        self.backend.negotiate = negotiate
        done, answers = self.spawn(lambda: self.broker.offer(self.cap, self.sid, SDP))
        self.assertTrue(entered.wait(1))
        self.broker.shutdown()
        self.assertEqual(self.broker.challenge(self.device).status, 503)
        self.assertEqual(self.broker.offer(self.cap, self.sid, SDP).status, 503)
        self.assertEqual(self.gate.active_count, 1)
        self.assertEqual(self.broker.stop(self.cap, self.sid).status, 200)
        release.set()
        self.assertTrue(done.wait(1))
        self.assertEqual(answers[0].status, 502)
        self.assertTrue(self.broker.wait_cleanup(1))
        self.assertEqual(self.gate.active_count, 0)
        self.assertEqual(self.backend.provider_calls, 1, 'only pre-shutdown offer may run')

    def test_force_retries_round_robin_with_false_acknowledgements(self):
        from broker.core import CLEANUP_MAXIMUM
        self.broker.stop(self.cap, self.sid)
        self.gate = Admission(CLEANUP_MAXIMUM + 1)
        self.broker = Broker(self.records.get, backend=self.backend,
                             admission=self.gate, clock=lambda: self.now)
        calls = []
        def force_stop(sid):
            calls.append(sid)
            return False
        self.backend.force_stop = force_stop
        bodies = []
        for index in range(CLEANUP_MAXIMUM + 1):
            key = secrets.token_bytes(32)
            device = identity(key)
            self.records[device] = dict(key=key, owner='o', profile='p',
                                       hermes_session=str(index), paired=True, revoked=False)
            with self.broker.lock:
                token = self.broker._cap(self.broker._approved(device), 'live:start', None, 160)
            body = self.broker.start(token, str(index)).body
            self.assertEqual(self.broker.offer(body['session_capability'],
                                              body['session_id'], SDP).status, 200)
            bodies.append(body)
        self.broker.shutdown()
        self.assertTrue(self.broker.wait_cleanup(1))
        self.now += 5
        calls.clear()
        for _ in range(10):
            self.broker.tick()
            self.assertTrue(self.broker.wait_cleanup(1))
            self.assertEqual(self.gate.active_count, len(bodies))
        counts = [calls.count(body['session_id']) for body in bodies]
        self.assertGreater(counts[-1], 0, 'fifth session must not starve')
        self.assertLessEqual(max(counts) - min(counts), 1)
        self.assertEqual(len(calls), 10 * CLEANUP_MAXIMUM)
        self.backend.force_stop = lambda sid: True
        for _ in range(2):
            self.broker.tick()
            self.assertTrue(self.broker.wait_cleanup(1))
        self.assertEqual(self.gate.active_count, 0)

    def test_force_lane_escalates_while_stop_is_blocked(self):
        self.assertEqual(self.broker.offer(self.cap, self.sid, SDP).status, 200)
        stopping, stop_release = self.blocker('stop')
        forcing, force_release = self.blocker('force_stop')
        self.broker.stop(self.cap, self.sid)
        self.assertTrue(stopping.wait(1))
        self.now += 5
        tick_done, _ = self.spawn(self.broker.tick)
        self.assertTrue(forcing.wait(1), 'force lane must bypass blocked ordinary stop')
        self.assertTrue(tick_done.wait(.5))
        for _ in range(20):
            self.broker.tick()
            self.broker.stop(self.cap, self.sid)
        with self.broker.lock:
            self.assertEqual(len(self.broker._cleanup_workers), 2)
        self.assertEqual(self.gate.active_count, 1)
        force_release.set()
        self.assertFalse(self.broker.wait_cleanup(.05))
        self.assertEqual(self.gate.active_count, 1, 'force ack cannot bypass outstanding stop')
        stop_release.set()
        self.assertTrue(self.broker.wait_cleanup(1))
        self.assertEqual(self.gate.active_count, 0)

    def test_cleanup_ack_cannot_release_replacement_session(self):
        self.broker.offer(self.cap, self.sid, SDP)
        entered, release = self.blocker('stop')
        self.broker.stop(self.cap, self.sid)
        self.assertTrue(entered.wait(1))
        with self.broker.lock:
            old = self.broker.sessions[self.sid]
            replacement = dict(old, generation='replacement', state='ACTIVE',
                               cleanup_running={False: False, True: False})
            self.broker.sessions[self.sid] = replacement
        release.set()
        self.assertTrue(self.broker.wait_cleanup(1))
        self.assertEqual(replacement['state'], 'ACTIVE')
        self.assertEqual(self.gate.active_count, 1)

    def test_stale_offer_cannot_activate_replaced_session(self):
        entered, release = threading.Event(), threading.Event()
        self.releases.append(release)
        original = self.backend.negotiate
        def negotiate(*args):
            entered.set()
            release.wait()
            return original(*args)
        self.backend.negotiate = negotiate
        done, results = self.spawn(lambda: self.broker.offer(self.cap, self.sid, SDP))
        self.assertTrue(entered.wait(1))
        with self.broker.lock:
            old = self.broker.sessions[self.sid]
            self.broker.sessions[self.sid] = dict(old, generation='replacement')
        release.set()
        self.assertTrue(done.wait(1))
        self.assertEqual(results[0].status, 502, 'stale negotiation must not be accepted')
        self.assertEqual(self.broker.sessions[self.sid]['state'], 'NEGOTIATING')
        self.assertEqual(self.gate.active_count, 1)

    def test_blocked_stop_allows_auth_and_offer_completion(self):
        negotiating, offer_release = threading.Event(), threading.Event()
        self.releases.append(offer_release)
        original = self.backend.negotiate
        def negotiate(*args):
            negotiating.set()
            offer_release.wait()
            return original(*args)
        self.backend.negotiate = negotiate
        offer_done, answers = self.spawn(lambda: self.broker.offer(self.cap, self.sid, SDP))
        self.assertTrue(negotiating.wait(1))
        stopping, stop_release = self.blocker('stop')
        stop_done, _ = self.spawn(lambda: self.broker.stop(self.cap, self.sid))
        self.assertTrue(stopping.wait(1))
        challenge_done, challenges = self.spawn(lambda: self.broker.challenge(self.device))
        self.assertTrue(challenge_done.wait(.5), 'cleanup holds broker lock against auth')
        self.assertEqual(challenges[0].status, 200)
        self.assertTrue(stop_done.wait(.5), 'public stop must not await backend cleanup')
        offer_release.set()
        self.assertTrue(offer_done.wait(.5), 'cleanup deadlocks negotiation completion')
        self.assertEqual(answers[0].status, 502)
        self.assertEqual(self.gate.active_count, 1)
        stop_release.set()
        self.assertTrue(self.broker.wait_cleanup(1))
        self.assertEqual(self.gate.active_count, 0)
