"""Bounded loopback-only HTTP transport for the TTS delivery test service.

Hard limits, deliberately mirrored from ``broker/http_api.py``:

* loopback bind only (``127.0.0.1``); at most four worker threads, saturation
  closes the connection without spawning another worker;
* one absolute deadline covers header and body; a byte-drip cannot extend it;
* cumulative request-line + header bytes are capped (431 on overflow);
* ``POST /challenge`` body <= 1024 bytes, ``POST /audio`` body <= 2048 bytes;
* every other method is 405, every query/fragment/pct-encoded path is 400,
  unknown paths are 404, and no response is ever a redirect;
* default logging is disabled and no exception text reaches a client.

Nothing here generates audio: the single verified MP3 blob is injected.
"""
import json
import math
import re
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from server_tts.audio import load_audio
from server_tts.core import MAX_AUDIO_BYTES, TTSDelivery

MAX_HEADER = 32768
CHALLENGE_BODY = 1024
AUDIO_BODY = 2048
MAX_WORKERS = 4
MAX_TIMEOUT = 30
ROUTES = ("/challenge", "/audio")
BODY_LIMIT = {"/challenge": CHALLENGE_BODY, "/audio": AUDIO_BODY}


class RequestFailure(Exception):
    def __init__(self, status):
        self.status = status


class HeaderReader:
    """Bound cumulative request-line/header bytes before stdlib parsing."""

    def __init__(self, handler, source):
        self.handler, self.source, self.remaining = handler, source, MAX_HEADER
        self.buffer = bytearray()

    def readline(self, size=-1):
        limit = min(size if size >= 0 else MAX_HEADER + 1, self.remaining + 1)
        while True:
            self.handler.remaining_timeout()
            newline = self.buffer.find(b'\n', 0, limit)
            count = newline + 1 if newline >= 0 else min(len(self.buffer), limit)
            if newline >= 0 or count == limit:
                break
            try:
                chunk = self.source.read1(min(4096, limit - len(self.buffer)))
            except (TimeoutError, OSError):
                raise RequestFailure(408) from None
            if not chunk:
                count = len(self.buffer)
                break
            self.buffer.extend(chunk)
        line = bytes(self.buffer[:count])
        del self.buffer[:count]
        self.remaining -= count
        if self.remaining < 0:
            raise RequestFailure(431)
        return line

    def read1(self, size):
        self.handler.remaining_timeout()
        if self.buffer:
            chunk = bytes(self.buffer[:size])
            del self.buffer[:size]
            return chunk
        return self.source.read1(size)

    def __getattr__(self, name):
        return getattr(self.source, name)


class TTSHTTPServer(ThreadingHTTPServer):
    """Explicitly injected offline fixture, at most four HTTP worker threads.

    Saturation closes the accepted connection without reading or spawning a
    worker. Call ``shutdown`` from another thread, then ``server_close``.
    """
    daemon_threads = False
    block_on_close = True
    request_queue_size = 4
    allow_reuse_address = False

    def __init__(self, registry, *, audio_path, host='127.0.0.1', port=18766,
                 request_timeout=5.0, clock=time.monotonic):
        if host != '127.0.0.1':
            raise ValueError('loopback binding required')
        if type(port) is not int or not 0 <= port <= 65535:
            raise ValueError('invalid port')
        if (isinstance(request_timeout, bool) or not isinstance(request_timeout, (int, float))
                or not math.isfinite(request_timeout) or not 0 < request_timeout <= MAX_TIMEOUT):
            raise ValueError('invalid timeout')
        if not callable(registry):
            raise ValueError('registry callable required')
        self.delivery = TTSDelivery(registry, audio=load_audio(audio_path, MAX_AUDIO_BYTES), clock=clock)
        self.request_timeout = request_timeout
        self.permits = threading.BoundedSemaphore(MAX_WORKERS)
        super().__init__((host, port), Handler)

    def process_request(self, request, client_address):
        if not self.permits.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except Exception:
            self.permits.release()
            self.shutdown_request(request)

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.permits.release()

    def handle_error(self, request, client_address):
        # The stdlib default prints exception traces and may expose credentials.
        pass


class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.0'

    def log_message(self, format, *args):
        pass

    def remaining_timeout(self):
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            raise RequestFailure(408)
        self.connection.settimeout(remaining)

    def handle_one_request(self):
        self.deadline = time.monotonic() + self.server.request_timeout
        self.rfile = HeaderReader(self, self.rfile)
        self.close_connection = True
        try:
            super().handle_one_request()
        except RequestFailure as error:
            self.reply_json(error.status, {'error': 'invalid_request'})
        except Exception:
            self.reply_json(500, {'error': 'internal_error'})
        finally:
            self.close_connection = True

    def send_error(self, code, message=None, explain=None):
        # Never echo stdlib request-line diagnostics or raw exceptions.
        self.reply_json(code, {'error': 'invalid_request'})

    def reply_json(self, status, body):
        data = json.dumps(body, separators=(',', ':'), allow_nan=False).encode()
        self._headers(status, 'application/json', len(data))
        self._write(data)

    def reply_audio(self, data):
        self._headers(200, 'audio/mpeg', len(data))
        self._write(data)

    def _headers(self, status, content_type, length):
        self.close_connection = True
        self.send_response_only(status)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(length))
        self.send_header('Cache-Control', 'no-store')
        self.send_header('Connection', 'close')
        self.send_header('X-Content-Type-Options', 'nosniff')
        self.end_headers()

    def _write(self, data):
        try:
            self.connection.settimeout(self.server.request_timeout)
            self.wfile.write(data)
        except (OSError, TimeoutError):
            pass

    def body(self, maximum):
        if self.headers.get_all('Transfer-Encoding') or self.headers.get_all('Content-Encoding'):
            raise RequestFailure(400)
        lengths = self.headers.get_all('Content-Length', [])
        if not lengths:
            raise RequestFailure(411)
        if len(lengths) != 1 or not re.fullmatch(r'[0-9]{1,10}', lengths[0]):
            raise RequestFailure(400)
        length = int(lengths[0])
        if length > maximum:
            raise RequestFailure(413)
        if self.headers.get_all('Content-Type', []) != ['application/json']:
            raise RequestFailure(415)
        chunks = []
        while length:
            self.remaining_timeout()
            try:
                chunk = self.rfile.read1(min(length, 4096))
            except (TimeoutError, OSError):
                raise RequestFailure(408) from None
            if not chunk:
                raise RequestFailure(400)
            chunks.append(chunk)
            length -= len(chunk)
        try:
            body = json.loads(b''.join(chunks).decode('utf-8'), object_pairs_hook=self._unique,
                              parse_constant=self._reject)

            if not isinstance(body, dict):
                raise ValueError('object required')
            return body
        except (ValueError, TypeError, UnicodeError, RecursionError):
            raise RequestFailure(400) from None

    @staticmethod
    def _unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError('duplicate key')
            result[key] = value
        return result

    @staticmethod
    def _reject(value):
        raise ValueError('invalid constant')

    def do_POST(self):
        if '?' in self.path or '#' in self.path or '%' in self.path:
            raise RequestFailure(400)
        if self.path not in ROUTES:
            raise RequestFailure(404)
        body = self.body(BODY_LIMIT[self.path])
        delivery = self.server.delivery
        if self.path == '/challenge':
            if set(body) != {'device_id'} or not isinstance(body['device_id'], str):
                raise RequestFailure(400)
            result = delivery.challenge(body['device_id'])
        else:
            if set(body) != {'device_id', 'nonce', 'mac'} or not all(
                    isinstance(v, str) for v in body.values()):
                raise RequestFailure(400)
            result = delivery.audio(body['device_id'], body['nonce'], body['mac'])
        if result.audio is not None:
            self.reply_audio(result.audio)
        else:
            self.reply_json(result.status, result.body)

    def do_GET(self):
        self.reply_json(405, {'error': 'method_not_allowed'})

    do_HEAD = do_GET
    do_PUT = do_GET
    do_PATCH = do_GET
    do_DELETE = do_GET
    do_OPTIONS = do_GET
