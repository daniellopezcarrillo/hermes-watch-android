#!/usr/bin/env python3
"""Cross-language live-broker challenge fixture: actual Python broker + actual Java client.

Runs the *real* ``broker.core.Broker.challenge`` under a deliberately fractional
monotonic clock, proves it emits an **integral** ``expires_at``, then compiles and
runs the *real* ``LiveContract`` against that exact wire body and asserts the Java
canonical message and HMAC match an independent Python derivation. A fractional
expiry is fed back in to prove the client rejects it instead of truncating a float.

No upstream checkout, no network, no package install: temporary files live only in
the system temp directory. Usable as a script and imported by the unittest wrapper.
"""
import base64
import hashlib
import hmac
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from broker.core import Broker, ProviderStub, device_id, integral_expiry  # noqa: E402

# Public TEST ONLY identity; never a deployed key.
KEY = bytes(range(32))
DEVICE = device_id(KEY)
FRACTION = 123456.789
# Non-ASCII values exercise the ensure_ascii escaping of the signed canonical JSON.
OWNER = 'café-owner'
PROFILE = 'watch-Ω'
SESSION = 'sessión-1'
RECORD = dict(key=KEY, owner=OWNER, profile=PROFILE, hermes_session=SESSION,
              paired=True, revoked=False)
EXPECTED_EXPIRY = 123486  # floor(123456.789) + 30
CONTRACT = ROOT / 'app/src/main/java/com/klywo/hermeswatch/livecall/LiveContract.java'


def registry(device):
    return RECORD if device == DEVICE else None


def java_tools():
    """Locate an installed JDK: JAVA_HOME, PATH or the ignored .tooling/ JDK."""
    candidates = []
    if os.environ.get('JAVA_HOME'):
        candidates.append(Path(os.environ['JAVA_HOME']) / 'bin')
    found = shutil.which('javac')
    if found:
        candidates.append(Path(found).parent)
    candidates.extend(sorted((ROOT / '.tooling').glob('jdk-*/bin')))
    for directory in candidates:
        java, javac = directory / 'java', directory / 'javac'
        if java.is_file() and javac.is_file():
            return java, javac
    return None, None


def json_jar():
    """Locate the org.json (test-only) jar without touching the app dependency set."""
    env = os.environ.get('JSON_JAR')
    if env and Path(env).is_file():
        return Path(env)
    local = ROOT / '.tooling/json.jar'
    if local.is_file():
        return local
    cache = ROOT / '.tooling/gradle-home/caches'
    for jar in sorted(cache.glob('**/org.json/json/**/*.jar')):
        return jar
    return None


def run(command):
    print('+ ' + ' '.join(map(str, command)), flush=True)
    result = subprocess.run(list(map(str, command)), cwd=ROOT, capture_output=True, text=True)
    assert result.returncode == 0, 'command failed ({0}): {1}\n{2}\n{3}'.format(
        result.returncode, command, result.stdout, result.stderr)
    return result


def main():
    # Evidence gate: never emit a PASS from an interpreter with assertions removed.
    if not __debug__ or sys.flags.optimize:
        raise SystemExit('Optimized Python is unsupported: interop evidence requires assertions')
    java, javac = java_tools()
    assert java and javac, 'JDK required (JAVA_HOME, PATH or .tooling/jdk-*); no upstream checkout is used'
    jar = json_jar()
    assert jar and jar.is_file(), 'org.json jar required (JSON_JAR, .tooling/json.jar or Gradle cache)'

    broker = Broker(registry, backend=ProviderStub(), clock=lambda: FRACTION)
    response = broker.challenge(DEVICE)
    assert response.status == 200, response.body
    body = response.body

    # 1. A fractional monotonic clock must still emit an integral expiry.
    assert isinstance(body['expires_at'], int) and not isinstance(body['expires_at'], bool), body['expires_at']
    assert body['expires_at'] == EXPECTED_EXPIRY, body['expires_at']
    assert integral_expiry(FRACTION, 30) == EXPECTED_EXPIRY
    # The wire body is exactly what the loopback adapter serializes: unsorted, ASCII-escaped.
    wire = json.dumps(body, separators=(',', ':')).encode('utf-8')
    assert b'"expires_at":123486' in wire and b'123486.' not in wire, wire

    # 2. Independent Python canonical message and MAC: the exact input broker.authenticate() signs.
    canonical = json.dumps(body, sort_keys=True, separators=(',', ':'))
    expected_mac = base64.b64encode(hmac.digest(KEY, canonical.encode('utf-8'), 'sha256')).decode()

    with tempfile.TemporaryDirectory(prefix='liveinterop-') as work:
        work = Path(work)
        classes = work / 'classes'
        classes.mkdir()
        (work / 'challenge.bin').write_bytes(wire)
        # Same body with a fractional expiry: the client must reject, not truncate.
        (work / 'fraction.bin').write_bytes(wire.replace(b'"expires_at":123486', b'"expires_at":123486.789'))
        run([javac, '-cp', jar, '-d', classes, CONTRACT, Path(__file__).with_name('LiveInteropProbe.java')])
        classpath = str(classes) + os.pathsep + str(jar)

        def probe(name):
            return subprocess.run([str(java), '-cp', classpath, 'LiveInteropProbe', KEY.hex(), str(work / name)],
                                  cwd=ROOT, capture_output=True, text=True)

        good = probe('challenge.bin')
        assert good.returncode == 0, good.stderr
        lines = good.stdout.splitlines()
        assert len(lines) == 3, good.stdout
        java_canonical, java_mac, java_expiry = lines
        assert java_canonical == canonical, 'canonical mismatch\nJava:   {0}\nPython: {1}'.format(java_canonical, canonical)
        assert java_mac == expected_mac, 'mac mismatch\nJava:   {0}\nPython: {1}'.format(java_mac, expected_mac)
        assert java_expiry == str(EXPECTED_EXPIRY), java_expiry

        bad = probe('fraction.bin')
        assert bad.returncode != 0 and not bad.stdout.strip(), 'fractional expiry accepted: {0!r}'.format(bad.stdout)

    assert canonical.encode('utf-8') == canonical.encode('ascii'), 'canonical must be ASCII-escaped'
    print('PASS cross-language live challenge: integer expiry from a fractional clock', flush=True)
    print('  expires_at={0} == floor({1}) + 30; Java canonical + HMAC match Python; fractional expiry rejected'
          .format(EXPECTED_EXPIRY, FRACTION), flush=True)


if __name__ == '__main__':
    main()
