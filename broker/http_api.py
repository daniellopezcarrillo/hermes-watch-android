"""Bounded loopback-only HTTP transport for the offline broker core.

No executable entrypoint, enrollment, provider login, or live backend wiring.
"""
import json
import math
import re
import socket
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from broker.core import Broker, ProviderStub, Response, bounded_json

MAXIMUM = 65536
SESSION_ROUTE = re.compile(r'/sessions/([A-Za-z0-9_-]{32})/(offer|stop|events)\Z')


class RequestFailure(Exception):
    def __init__(self, status):
        self.status = status


class HeaderReader:
    """Bound cumulative request-line/header bytes before stdlib parsing."""
    def __init__(self, handler, source):
        self.handler, self.source, self.remaining = handler, source, MAXIMUM
        self.buffer = bytearray()

    def readline(self, size=-1):
        limit = min(size if size >= 0 else MAXIMUM + 1, self.remaining + 1)
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


class BrokerHTTPServer(ThreadingHTTPServer):
    """Explicitly injected offline fixture, at most four HTTP worker threads.

    Saturation closes the accepted connection without reading or spawning a
    worker. The kernel listen backlog is bounded; there is no Python work queue.
    Call shutdown from a thread other than the serve_forever thread, then close.
    """
    daemon_threads = False
    block_on_close = True
    request_queue_size = 4
    allow_reuse_address = False

    def __init__(self, registry, *, backend, port=0, host='127.0.0.1',
                 request_timeout=2.0, clock=time.monotonic, admission=None):
        if host != '127.0.0.1':
            raise ValueError('loopback binding required')
        if type(port) is not int or not 0 <= port <= 65535:
            raise ValueError('invalid port')
        if (isinstance(request_timeout, bool) or not isinstance(request_timeout, (int, float))
                or not math.isfinite(request_timeout) or not 0 < request_timeout <= 30):
            raise ValueError('invalid timeout')
        if type(backend) is not ProviderStub:
            raise ValueError('offline ProviderStub required')
        self.broker = Broker(registry, backend=backend, clock=clock, admission=admission)
        self.request_timeout = request_timeout
        self.permits = threading.BoundedSemaphore(4)
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

    def service_actions(self):
        self.broker.tick()

    def shutdown(self):
        self.broker.shutdown()
        super().shutdown()

    def server_close(self):
        self.broker.shutdown()
        super().server_close()
        self.broker.wait_cleanup(self.request_timeout)


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
            self.reply(Response(error.status, {'error': 'invalid_request'}))
        except Exception:
            self.reply(Response(500, {'error': 'internal_error'}))
        finally:
            self.close_connection = True

    def send_error(self, code, message=None, explain=None):
        # Never echo stdlib request-line diagnostics or raw exceptions.
        self.reply(Response(code, {'error': 'invalid_request'}))

    def reply(self, response):
        data = json.dumps(response.body, separators=(',', ':'), allow_nan=False).encode()
        try:
            self.close_connection = True
            self.connection.settimeout(self.server.request_timeout)
            self.send_response_only(response.status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(data)))
            self.send_header('Cache-Control', 'no-store')
            self.send_header('Connection', 'close')
            self.send_header('X-Content-Type-Options', 'nosniff')
            if response.status == 401:
                self.send_header('WWW-Authenticate', 'Bearer')
            self.end_headers()
            self.wfile.write(data)
        except (OSError, TimeoutError):
            pass

    def token(self):
        values = self.headers.get_all('Authorization', [])
        if len(values) != 1 or not re.fullmatch(r'Bearer [A-Za-z0-9_-]{43}', values[0]):
            return None
        return values[0][7:]

    def body(self):
        if self.headers.get_all('Transfer-Encoding') or self.headers.get_all('Content-Encoding'):
            raise RequestFailure(400)
        lengths = self.headers.get_all('Content-Length', [])
        if not lengths:
            raise RequestFailure(411)
        if len(lengths) != 1 or not re.fullmatch(r'[0-9]{1,10}', lengths[0]):
            raise RequestFailure(400)
        length = int(lengths[0])
        if length > MAXIMUM:
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
            payload = b''.join(chunks)
            body = bounded_json([payload], maximum=MAXIMUM)
            if not isinstance(body, dict):
                raise ValueError('object required')
            return body, payload
        except (ValueError, TypeError, UnicodeError, RecursionError):
            raise RequestFailure(400) from None

    def do_POST(self):
        if '?' in self.path or '#' in self.path or '%' in self.path:
            raise RequestFailure(400)
        route = SESSION_ROUTE.fullmatch(self.path)
        if self.path not in ('/challenge', '/authenticate', '/sessions') and not route:
            raise RequestFailure(404)
        body, payload = self.body()
        broker, token = self.server.broker, self.token()
        if self.path == '/challenge':
            if set(body) != {'device_id'} or not isinstance(body['device_id'], str):
                raise RequestFailure(400)
            result = broker.challenge(body['device_id'])
        elif self.path == '/authenticate':
            if set(body) != {'device_id', 'nonce', 'mac'} or not all(isinstance(v, str) for v in body.values()):
                raise RequestFailure(400)
            result = broker.authenticate(body['device_id'], body['nonce'], body['mac'])
        elif self.path == '/sessions':
            if set(body) != {'request_id'}:
                raise RequestFailure(400)
            result = broker.start(token, body['request_id'])
        else:
            sid, action = route.groups()
            if action == 'offer':
                if set(body) not in ({'sdp'}, {'request_id', 'sdp'}):
                    raise RequestFailure(400)
                result = broker.offer(token, sid, body['sdp'])
            elif action == 'stop':
                if set(body) not in (set(), {'request_id'}):
                    raise RequestFailure(400)
                result = broker.stop(token, sid)
            else:
                result = broker.events(token, sid, payload)
        self.reply(result)

    def do_GET(self):
        self.reply(Response(405, {'error': 'method_not_allowed'}))

    do_HEAD = do_GET
    do_PUT = do_GET
    do_PATCH = do_GET
    do_DELETE = do_GET
    do_OPTIONS = do_GET
