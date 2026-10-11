"""Real loopback HTTP tests with an injected registry and audio path.

Exercises only ephemeral offline identities and a synthetic MP3 fixture; no
gateway, provider, network egress or live TTS is involved.
"""
import base64
import contextlib
import http.client
import io
import json
import os
import secrets
import socket
import tempfile
import threading
import time
import unittest

from server_tts import http_api
from server_tts.audio import load_audio
from server_tts.http_api import TTSHTTPServer
from server_tts.registry import FixtureRegistry
from tests.server_tts.fixtures import MP3_FIXTURE, device_id, mac


class HTTPTests(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.audio_path = os.path.join(self.dir.name, 'spoken.mp3')
        with open(self.audio_path, 'wb') as handle:
            handle.write(MP3_FIXTURE)
        self.now = 100.0
        self.key = secrets.token_bytes(32)
        self.device = device_id(self.key)
        self.registry = FixtureRegistry({self.device: {'key': self.key, 'approved': True}})
        self.server = TTSHTTPServer(self.registry.lookup, audio_path=self.audio_path,
                                    port=0, request_timeout=0.4, clock=lambda: self.now)
        self.thread = threading.Thread(target=self.server.serve_forever,
                                       kwargs={'poll_interval': 0.02})
        self.thread.start()
        self.addCleanup(self.finish)

    def finish(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(2)
        self.assertFalse(self.thread.is_alive())

    # -- request helpers -----------------------------------------------------

    def request(self, path, body, method='POST', content_type='application/json',
                raw_body=None):
        connection = http.client.HTTPConnection(*self.server.server_address, timeout=2)
        headers = {}
        if content_type is not None:
            headers['Content-Type'] = content_type
        payload = raw_body if raw_body is not None else json.dumps(body).encode()
        try:
            connection.request(method, path, payload, headers)
            response = connection.getresponse()
            return response.status, dict(response.getheaders()), response.read()
        finally:
            connection.close()

    def post(self, path, body, **kwargs):
        status, headers, data = self.request(path, body, **kwargs)
        parsed = json.loads(data) if headers.get('Content-Type') == 'application/json' else data
        return status, parsed

    def raw(self, data):
        with socket.create_connection(self.server.server_address, timeout=2) as sock:
            sock.sendall(data)
            result = bytearray()
            while True:
                try:
                    block = sock.recv(4096)
                except (TimeoutError, OSError):
                    break
                if not block:
                    break
                result.extend(block)
            return bytes(result)

    def challenge(self, device=None):
        status, body = self.post('/challenge', {'device_id': device or self.device})
        self.assertEqual(status, 200)
        return body

    def fetch(self, key=None, device=None):
        key = key or self.key
        device = device or self.device
        ch = self.challenge(device)
        status, headers, data = self.request(
            '/audio', {'device_id': device, 'nonce': ch['nonce'], 'mac': mac(key, device, ch['nonce'])})
        return status, headers, data

    # -- tests ---------------------------------------------------------------

    def test_two_authenticated_fetches_return_identical_fixed_bytes(self):
        first_status, headers, first = self.fetch()
        self.assertEqual(first_status, 200)
        self.assertEqual(headers['Content-Type'], 'audio/mpeg')
        self.assertEqual(int(headers['Content-Length']), len(MP3_FIXTURE))
        self.assertEqual(headers['Cache-Control'], 'no-store')
        self.assertEqual(first, MP3_FIXTURE)
        second_status, _, second = self.fetch()
        self.assertEqual(second_status, 200)
        self.assertEqual(second, first)

    def test_returns_the_injected_server_path_not_a_request_selected_file(self):
        # Any attempt to influence the file through the body is rejected.
        self.assertEqual(self.post('/audio', {'device_id': self.device, 'nonce': 'n',
                                              'mac': 'm', 'path': '/etc/passwd'})[0], 400)
        # No GET/HEAD file route exists.
        self.assertEqual(self.post('/audio/../../etc/passwd', {}, method='GET')[0], 405)

    def test_denies_unknown_and_pending_and_revoked_devices(self):
        stranger = secrets.token_bytes(32)
        self.assertEqual(self.post('/challenge', {'device_id': device_id(stranger)})[0], 401)
        self.registry._records[self.device]['approved'] = False  # pending / not approved
        self.assertEqual(self.post('/challenge', {'device_id': self.device})[0], 401)
        del self.registry._records[self.device]  # revoked / forgotten
        self.assertEqual(self.post('/challenge', {'device_id': self.device})[0], 401)

    def test_nonce_replay_and_wrong_mac_denied(self):
        ch = self.challenge()
        good = mac(self.key, self.device, ch['nonce'])
        self.assertEqual(self.request('/audio', {'device_id': self.device, 'nonce': ch['nonce'],
                                                 'mac': good})[0], 200)
        self.assertEqual(self.request('/audio', {'device_id': self.device, 'nonce': ch['nonce'],
                                                 'mac': good})[0], 401)  # replay
        ch2 = self.challenge()
        bad = base64.b64encode(secrets.token_bytes(32)).decode()
        self.assertEqual(self.request('/audio', {'device_id': self.device, 'nonce': ch2['nonce'],
                                                 'mac': bad})[0], 401)
        self.assertEqual(self.request('/audio', {'device_id': self.device, 'nonce': ch2['nonce'],
                                                 'mac': mac(self.key, self.device, ch2['nonce'])})[0], 401)

    def test_expired_nonce_denied(self):
        ch = self.challenge()
        self.now += 30.5
        self.assertEqual(self.request('/audio', {'device_id': self.device, 'nonce': ch['nonce'],
                                                 'mac': mac(self.key, self.device, ch['nonce'])})[0], 401)

    def test_get_and_query_and_unknown_paths_rejected_without_redirects(self):
        status, body = self.post('/challenge', {'device_id': self.device}, method='GET')
        self.assertEqual(status, 405)
        for path in ('/challenge?device_id=' + self.device, '/challenge#' + self.device,
                     '/challenge%3f' + self.device, '/audio?x=1'):
            self.assertEqual(self.post(path, {'device_id': self.device})[0], 400)
        self.assertEqual(self.post('/files/' + self.device, {})[0], 404)
        self.assertEqual(self.post('/', {}, method='GET')[0], 405)

    def test_transport_bounds_and_sanitized_errors(self):
        base = b'POST /audio HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n'
        cases = [
            (b'\r\n', 411),
            (b'Content-Length: 2049\r\n\r\n', 413),                     # /audio body cap
            (b'Content-Length: 2\r\nContent-Length: 2\r\n\r\n{}', 400),  # duplicate
            (b'Content-Length: -1\r\n\r\n', 400),
            (b'Transfer-Encoding: chunked\r\nContent-Length: 2\r\n\r\n{}', 400),
            (b'Content-Encoding: gzip\r\nContent-Length: 2\r\n\r\n{}', 400),
            (b'X-Large: ' + b'x' * 40000 + b'\r\nContent-Length: 2\r\n\r\n{}', 431),
        ]
        for suffix, status in cases:
            with self.subTest(status=status, prefix=suffix[:24]):
                response = self.raw(base + suffix)
                self.assertIn((' ' + str(status) + ' ').encode(), response.split(b'\r\n')[0])
                self.assertIn(b'{"error":"invalid_request"}', response)
        # /challenge has the tighter 1024-byte cap.
        challenge = (b'POST /challenge HTTP/1.1\r\nHost: localhost\r\n'
                     b'Content-Type: application/json\r\nContent-Length: 1025\r\n\r\n')
        self.assertIn(b' 413 ', self.raw(challenge).split(b'\r\n')[0])
        for body in (b'{', b'\xff', b'[]', b'{"a":1,"a":2}', b'{"a":NaN}'):
            response = self.raw(base + b'Content-Length: ' + str(len(body)).encode() + b'\r\n\r\n' + body)
            self.assertIn(b' 400 ', response.split(b'\r\n')[0])

    def test_header_drip_and_slow_body_cannot_extend_the_deadline(self):
        for prefix in (b'POST /challenge HTTP/1.1\r\nHost: ',
                       b'POST /challenge HTTP/1.1\r\nContent-Type: application/json\r\nContent-Length: 99\r\n\r\n'):
            with self.subTest(prefix=prefix[:24]), socket.create_connection(
                    self.server.server_address, timeout=2) as sock:
                sock.sendall(prefix)
                started = time.monotonic()
                for _ in range(6):
                    time.sleep(0.08)
                    try:
                        sock.sendall(b' ')
                    except OSError:
                        break
                self.assertIn(b' 408 ', sock.recv(4096))
                self.assertLess(time.monotonic() - started, 1.2)

    def test_saturation_closes_fifth_connection_without_new_worker(self):
        clients = []
        try:
            for _ in range(4):
                # A complete request holds a worker while it waits, then the
                # client keeps the socket open reading the response.
                sock = socket.create_connection(self.server.server_address, timeout=2)
                sock.sendall(b'POST /challenge HTTP/1.1\r\nHost: ')
                clients.append(sock)
            deadline = time.monotonic() + 1
            while (not hasattr(self.server._threads, '__len__')
                   or len(self.server._threads) < 4) and time.monotonic() < deadline:
                time.sleep(0.005)
            self.assertEqual(len(self.server._threads), 4)
            with socket.create_connection(self.server.server_address, timeout=1) as extra:
                self.assertEqual(extra.recv(1), b'')
            self.assertEqual(len(self.server._threads), 4)
        finally:
            for sock in clients:
                sock.close()

    def test_no_default_logging_or_exception_text(self):
        captured = io.StringIO()
        with contextlib.redirect_stderr(captured):
            self.challenge()
            self.raw(b'INVALID SECRET_TOKEN HTTP/1.1\r\n\r\n')
            self.assertEqual(self.post('/challenge', {'device_id': ''})[0], 401)
        self.assertEqual(captured.getvalue(), '')

    def test_registry_exception_is_sanitized(self):
        def explode(_):
            raise RuntimeError('SECRET_BACKEND_CREDENTIAL')
        self.server.delivery.registry = explode
        status, body = self.post('/challenge', {'device_id': self.device})
        self.assertEqual((status, body), (401, {'error': 'unauthorized'}))
        self.assertNotIn('SECRET_BACKEND_CREDENTIAL', json.dumps(body))

    def test_loopback_only_and_fixture_configuration_rejected(self):
        for config in ({'host': '0.0.0.0'}, {'host': 'localhost'}, {'port': -1},
                       {'request_timeout': 0}, {'request_timeout': float('inf')},
                       {'request_timeout': 31}, {'registry': object()}):
            with self.subTest(config=config), self.assertRaises(ValueError):
                TTSHTTPServer(**dict({'registry': self.registry.lookup,
                                      'audio_path': self.audio_path}, **config))
        self.assertEqual(self.server.server_address[0], '127.0.0.1')

    def test_default_port_is_the_staging_loopback_port(self):
        self.assertEqual(http_api.TTSHTTPServer.__init__.__kwdefaults__['port'], 18766)


class AudioLoaderTests(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)

    def write(self, data):
        path = os.path.join(self.dir.name, 'a.mp3')
        with open(path, 'wb') as handle:
            handle.write(data)
        return path

    def test_accepts_valid_mp3_within_cap(self):
        self.assertEqual(load_audio(self.write(MP3_FIXTURE)), MP3_FIXTURE)
        self.assertEqual(load_audio(self.write(b'ID3\x04\x00\x00' + b'\x00' * 10))[:3], b'ID3')

    def test_rejects_missing_empty_oversize_and_non_mp3(self):
        for path, data in ((os.path.join(self.dir.name, 'missing.mp3'), None),
                           (self.write(b''), b''),
                           (self.write(b'not mp3 at all'), b'not mp3 at all'),
                           (self.write(b'\xff\xfb' + b'x' * (http_api.MAX_AUDIO_BYTES)), None)):
            with self.subTest(path=path), self.assertRaises(ValueError):
                load_audio(path)


if __name__ == '__main__':
    unittest.main()
