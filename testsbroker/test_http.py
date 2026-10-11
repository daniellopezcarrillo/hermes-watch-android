"""Real loopback HTTP tests; only ephemeral offline identities/backends."""
import base64
import hmac
import http.client
import json
import secrets
import threading
import unittest

from broker.core import Admission, ProviderStub, device_id
from testsbroker.test_security import SDP


class HTTPTests(unittest.TestCase):
    def setUp(self):
        from broker.http_api import BrokerHTTPServer
        self.now = 100.0
        self.key = secrets.token_bytes(32)
        self.device = device_id(self.key)
        self.records = {self.device: dict(key=self.key, owner='owner', profile='p',
                                        hermes_session='h', paired=True, revoked=False)}
        self.backend = ProviderStub()
        self.server = BrokerHTTPServer(self.records.get, backend=self.backend,
                                       admission=Admission(2), clock=lambda: self.now,
                                       request_timeout=.3)
        self.thread = threading.Thread(target=self.server.serve_forever,
                                       kwargs={'poll_interval': .02})
        self.thread.start()
        self.addCleanup(self.finish)

    def finish(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(2)
        self.assertFalse(self.thread.is_alive())
        self.assertTrue(self.server.broker.wait_cleanup(1))

    def post(self, path, body, token=None, method='POST'):
        connection = http.client.HTTPConnection(*self.server.server_address, timeout=2)
        headers = {'Content-Type': 'application/json'}
        if token is not None:
            headers['Authorization'] = 'Bearer ' + token
        try:
            connection.request(method, path, json.dumps(body).encode(), headers)
            response = connection.getresponse()
            self.assertEqual(response.getheader('Cache-Control'), 'no-store')
            return response.status, json.loads(response.read())
        finally:
            connection.close()

    def authenticate(self):
        status, challenge = self.post('/challenge', {'device_id': self.device})
        self.assertEqual(status, 200)
        mac = base64.b64encode(hmac.digest(self.key, json.dumps(
            challenge, sort_keys=True, separators=(',', ':')).encode(), 'sha256')).decode()
        status, auth = self.post('/authenticate', dict(device_id=self.device,
                                                      nonce=challenge['nonce'], mac=mac))
        self.assertEqual(status, 200)
        self.assertEqual(auth['expires_at'], self.now + 60)
        return auth['capability']

    def reserve(self, request='request'):
        status, body = self.post('/sessions', {'request_id': request}, self.authenticate())
        self.assertEqual(status, 201)
        self.assertEqual(body['expires_at'], self.now + 120)
        return body

    def test_unauthorized_zero_upstream(self):
        self.assertEqual(self.post('/sessions', {'request_id': 'r'})[0], 401)
        for action in ('offer', 'stop', 'events'):
            self.assertEqual(self.post('/sessions/' + 'x' * 32 + '/' + action,
                                       {'sdp': SDP} if action == 'offer' else {})[0], 401)
        self.assertEqual(self.backend.provider_calls, 0)
        self.assertEqual(self.backend.gateway_calls, 0)

    def raw(self, data):
        import socket
        with socket.create_connection(self.server.server_address, timeout=2) as sock:
            sock.sendall(data)
            result = bytearray()
            while True:
                block = sock.recv(4096)
                if not block:
                    break
                result.extend(block)
            return bytes(result)

    def test_authenticated_lifecycle_replay_and_owner_scope(self):
        start = self.authenticate()
        status, first = self.post('/sessions', {'request_id': 'same'}, start)
        self.assertEqual(status, 201)
        self.assertEqual(self.post('/sessions', {'request_id': 'same'}, start), (201, first))
        self.assertEqual(self.post('/sessions', {'request_id': 'different'}, start)[0], 401)
        cap, sid = first['session_capability'], first['session_id']
        self.assertEqual(self.post('/sessions', {'request_id': 'bad-scope'}, cap)[0], 401)
        self.assertEqual(self.post('/sessions/' + sid + '/offer', {'sdp': SDP}, cap)[0], 200)
        self.assertEqual(self.backend.provider_calls, 1)
        self.assertEqual(self.post('/sessions/' + sid + '/events', {'type': 'status'}, cap),
                         (202, {'state': 'queued'}))
        self.assertEqual(self.post('/sessions/' + sid + '/events', {'type': 'tool.execute'}, cap)[0], 400)
        self.key = secrets.token_bytes(32)
        self.device = device_id(self.key)
        self.records[self.device] = dict(key=self.key, owner='other-owner', profile='other-p',
                                         hermes_session='other-h', paired=True, revoked=False)
        second = self.reserve('other')
        for action, body in [('offer', {'sdp': SDP}), ('stop', {}), ('events', {'type': 'status'})]:
            self.assertEqual(self.post('/sessions/' + sid + '/' + action, body,
                                       second['session_capability'])[0], 403)
        self.assertEqual(self.backend.provider_calls, 1)
        self.assertEqual(self.backend.gateway_calls, 0)
        self.assertEqual(self.post('/sessions/' + sid + '/stop', {}, cap)[0], 200)
        self.assertTrue(self.server.broker.wait_cleanup(1))
        self.assertEqual(self.post('/sessions/' + sid + '/stop', {}, cap),
                         (200, {'session_id': sid, 'state': 'CLOSED'}))
        self.assertEqual(self.server.broker.admission.active_count, 1)

    def test_revocation_expiry_tick_and_shutdown_cleanup(self):
        first = self.reserve()
        cap, sid = first['session_capability'], first['session_id']
        self.assertEqual(self.post('/sessions/' + sid + '/offer', {'sdp': SDP}, cap)[0], 200)
        self.records[self.device]['revoked'] = True
        self.assertEqual(self.post('/sessions/' + sid + '/events', {'type': 'status'}, cap)[0], 401)
        self.server.service_actions()
        self.assertTrue(self.server.broker.wait_cleanup(1))
        self.assertEqual(self.server.broker.admission.active_count, 0)
        self.records[self.device]['revoked'] = False
        token = self.authenticate()
        self.now += 60
        self.assertEqual(self.post('/sessions', {'request_id': 'expired'}, token)[0], 401)
        second = self.reserve('shutdown')
        self.assertEqual(self.post('/sessions/' + second['session_id'] + '/offer',
                                   {'sdp': SDP}, second['session_capability'])[0], 200)
        self.server.shutdown()
        self.thread.join(2)
        self.assertFalse(self.thread.is_alive())
        self.assertTrue(self.server.broker.wait_cleanup(1))
        self.assertEqual(self.server.broker.admission.active_count, 0)
        self.assertEqual(self.server.broker.challenge(self.device).status, 503)

    def test_transport_bounds_and_sanitized_errors(self):
        base = b'POST /sessions HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n'
        cases = [
            (b'\r\n', 411),
            (b'Content-Length: 65537\r\n\r\n', 413),
            (b'Content-Length: 2\r\nContent-Length: 2\r\n\r\n{}', 400),
            (b'Content-Length: -1\r\n\r\n', 400),
            (b'Content-Length: 2,2\r\n\r\n{}', 400),
            (b'Transfer-Encoding: chunked\r\nContent-Length: 2\r\n\r\n{}', 400),
            (b'Content-Encoding: gzip\r\nContent-Length: 2\r\n\r\n{}', 400),
            (b'X-Large: ' + b'x' * 65536 + b'\r\nContent-Length: 2\r\n\r\n{}', 431),
        ]
        for suffix, status in cases:
            with self.subTest(status=status, prefix=suffix[:30]):
                response = self.raw(base + suffix)
                self.assertIn((' ' + str(status) + ' ').encode(), response.split(b'\r\n')[0])
                self.assertIn(b'{"error":"invalid_request"}', response)
        for body in [b'{', b'\xff', b'[]', b'{"a":1,"a":2}', b'{"a":NaN}',
                     b'{"a":' + b'[' * 9 + b'0' + b']' * 9 + b'}']:
            with self.subTest(body=body):
                response = self.raw(base + b'Content-Length: ' + str(len(body)).encode() + b'\r\n\r\n' + body)
                self.assertIn(b' 400 ', response.split(b'\r\n')[0])
        self.assertEqual(self.backend.provider_calls, 0)

    def test_urls_methods_and_routing_overrides_rejected(self):
        cap = self.authenticate()
        for path in ['/sessions?capability=' + cap, '/sessions#' + cap,
                     '/sessions%3f' + cap, 'http://localhost/sessions']:
            status, body = self.post(path, {'request_id': 'r'}, cap)
            self.assertIn(status, (400, 404))
            self.assertNotIn(cap, json.dumps(body))
        self.assertEqual(self.post('/sessions', {'request_id': 'r'}, cap, method='GET')[0], 405)
        self.assertEqual(self.post('/sessions', {'request_id': 'r', 'owner': 'attacker'}, cap)[0], 400)
        self.assertEqual(self.backend.provider_calls, 0)
        self.assertEqual(self.server.broker.admission.active_count, 0)

    def test_header_drip_cannot_extend_absolute_deadline(self):
        import socket
        import time
        with socket.create_connection(self.server.server_address, timeout=2) as sock:
            sock.sendall(b'POST /challenge HTTP/1.1\r\nHost: ')
            done = threading.Event()
            def drip():
                while not done.wait(.06):
                    try:
                        sock.sendall(b'x')
                    except OSError:
                        break
            sender = threading.Thread(target=drip)
            sender.start()
            started = time.monotonic()
            try:
                response = sock.recv(4096)
                self.assertIn(b' 408 ', response)
                self.assertLess(time.monotonic() - started, .6)
            finally:
                done.set()
                sender.join(1)

    def test_slow_body_and_header_deadline(self):
        import socket
        import time
        for prefix in [b'POST /challenge HTTP/1.1\r\nHost: ',
                       b'POST /challenge HTTP/1.1\r\nContent-Type: application/json\r\nContent-Length: 99\r\n\r\n']:
            with self.subTest(prefix=prefix), socket.create_connection(self.server.server_address, timeout=2) as sock:
                sock.sendall(prefix)
                started = time.monotonic()
                for _ in range(6):
                    time.sleep(.08)
                    try:
                        sock.sendall(b' ')
                    except OSError:
                        break
                response = sock.recv(4096)
                self.assertIn(b' 408 ', response)
                self.assertLess(time.monotonic() - started, .8)
        self.assertEqual(self.backend.provider_calls, 0)

    def test_concurrency_saturation_closes_without_new_workers(self):
        import socket
        import time
        clients = []
        try:
            for _ in range(4):
                sock = socket.create_connection(self.server.server_address, timeout=2)
                sock.sendall(b'POST /challenge HTTP/1.1\r\n')
                clients.append(sock)
            deadline = time.monotonic() + 1
            while (not hasattr(self.server._threads, '__len__') or len(self.server._threads) < 4) and time.monotonic() < deadline:
                time.sleep(.005)
            self.assertEqual(len(self.server._threads), 4)
            with socket.create_connection(self.server.server_address, timeout=1) as extra:
                self.assertEqual(extra.recv(1), b'')
            self.assertEqual(len(self.server._threads), 4)
        finally:
            for sock in clients:
                sock.close()
        self.assertEqual(self.backend.provider_calls, 0)

    def test_no_default_logging_or_backend_exception_text(self):
        import contextlib
        import io
        cap = self.authenticate()
        captured = io.StringIO()
        with contextlib.redirect_stderr(captured):
            self.assertEqual(self.post('/sessions', {'request_id': 'r'}, cap)[0], 201)
            self.raw(b'INVALID SECRET_TOKEN HTTP/1.1\r\n\r\n')
        self.assertEqual(captured.getvalue(), '')
        first = self.server.broker.requests[(self.device, 'r')][1]
        def fail(*args):
            raise RuntimeError('SECRET_BACKEND_CREDENTIAL')
        self.backend.negotiate = fail
        status, body = self.post('/sessions/' + first['session_id'] + '/offer',
                                 {'sdp': SDP}, first['session_capability'])
        self.assertEqual((status, body), (502, {'error': 'backend_failure'}))
        self.assertTrue(self.server.broker.wait_cleanup(1))
        self.assertEqual(self.server.broker.admission.active_count, 0)

    def test_nonloopback_and_nonfixture_configuration_rejected(self):
        from broker.http_api import BrokerHTTPServer
        for config in [{'host': '0.0.0.0'}, {'host': 'localhost'}, {'port': -1},
                       {'request_timeout': 0}, {'request_timeout': float('inf')},
                       {'backend': object()}]:
            with self.subTest(config=config), self.assertRaises(ValueError):
                BrokerHTTPServer(self.records.get, **dict({'backend': self.backend}, **config))
        self.assertEqual(self.server.server_address[0], '127.0.0.1')


if __name__ == '__main__':
    unittest.main()
