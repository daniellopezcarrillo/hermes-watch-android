"""Standalone entrypoint for the TTS delivery test service.

Binds loopback only (default ``127.0.0.1:18766``). Put it behind host-owned
Tailscale Serve as HTTPS ``8445`` after review; do not expose it directly and
do not change the existing gadget port ``8444``.

    python3 -m server_tts --registry-dir <gateway-dir> --audio <fixed.mp3>

``--registry-dir`` is the dedicated gateway data directory that holds
``devices.json`` (the enrollment store). Approval is a *separate* fact: the
real Hermes ``PairingStore`` must report the device approved
(``is_approved("gadget", device_id)``). By default that pairing directory is
resolved from the running root exactly as the ``hermes`` CLI does
(``HERMES_HOME`` -> ``platforms/pairing``, else ``~/.hermes/...``); override it
with ``--pairing-dir``. Started from the repository root.
"""
import argparse
import sys

from server_tts.http_api import TTSHTTPServer
from server_tts.registry import DeviceStoreRegistry, PairingApprovalReader, default_pairing_dir


def build_parser():
    parser = argparse.ArgumentParser(prog='server_tts', description=__doc__)
    parser.add_argument('--registry-dir', required=True,
                        help='directory holding the gateway devices.json (enrollment)')
    parser.add_argument('--pairing-dir', default=None,
                        help='Hermes pairing dir holding gadget-approved.json '
                             '(default: the running root\'s CLI pairing dir)')
    parser.add_argument('--audio', required=True,
                        help='fixed, pre-generated MP3 file to serve (<=256 KiB)')
    parser.add_argument('--host', default='127.0.0.1', help='loopback host (fixed)')
    parser.add_argument('--port', type=int, default=18766, help='listen port (default 18766)')
    parser.add_argument('--timeout', type=float, default=5.0, help='absolute request timeout seconds')
    return parser


def main(argv=None):
    args = build_parser().parse_args(argv)
    pairing_dir = args.pairing_dir if args.pairing_dir is not None else default_pairing_dir()
    approvals = PairingApprovalReader(pairing_dir)
    registry = DeviceStoreRegistry(args.registry_dir, approvals=approvals)
    try:
        server = TTSHTTPServer(registry.lookup, audio_path=args.audio, host=args.host,
                               port=args.port, request_timeout=args.timeout)
    except ValueError as error:
        print('server_tts: %s' % error, file=sys.stderr)
        return 2
    host, port = server.server_address
    print('server_tts: listening on http://%s:%d (loopback; front with Tailscale Serve 8445)' % (host, port))
    print('server_tts: approvals from %s' % approvals.path)
    sys.stdout.flush()
    try:
        server.serve_forever(poll_interval=0.2)
    except KeyboardInterrupt:
        pass
    finally:
        server.shutdown()
        server.server_close()
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
