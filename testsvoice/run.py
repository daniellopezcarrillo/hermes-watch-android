#!/usr/bin/env python3
"""Compile isolated voice + existing codec with the repository's ignored JDK/jars."""
from pathlib import Path
import subprocess
import tempfile
root = Path(__file__).resolve().parents[1]
jdk = next((root / '.tooling').glob('jdk-*/bin'))
with tempfile.TemporaryDirectory(prefix='voice-tests-') as out:
    sources = list((root / 'app/src/main/java/com/klywo/hermeswatch/voice').glob('*.java'))
    sources += list((root / 'app/src/main/java/com/klywo/hermeswatch/protocol').glob('*.java'))
    sources += list((root / 'testsvoice').glob('*.java'))
    cp = str(root / '.tooling/json.jar')
    subprocess.run([str(jdk / 'javac'), '-Xlint:all', '-cp', cp, '-d', out, *map(str, sources)], check=True)
    # Android JSONException is checked, unlike the JVM json.jar implementation.
    android_jar = root / '.tooling/android-sdk/platforms/android-35/android.jar'
    production = [s for s in sources if 'testsvoice' not in s.parts]
    with tempfile.TemporaryDirectory(prefix='voice-android-compile-') as android_out:
        subprocess.run([str(jdk / 'javac'), '-Xlint:all', '-cp', str(android_jar),
                        '-d', android_out, *map(str, production)], check=True)
    print('PASS production compile against Android API 35 org.json', flush=True)
    subprocess.run([str(jdk / 'java'), '-ea', '-cp', out + ':' + cp,
                    'com.klywo.hermeswatch.voice.VoicePrimitivesTest'], check=True)
    import json
    import struct
    result = subprocess.check_output([str(jdk / 'java'), '-cp', out + ':' + cp,
                    'com.klywo.hermeswatch.voice.WireFixtures'], text=True).splitlines()
    expected = [
        json.dumps(dict(type='audio.start', id='fixture-1', rate=16000, format='pcm16', stream=255), separators=(',', ':')),
        (struct.pack('<BBH', 1, 255, 0) + bytes(1280)).hex(),
        (struct.pack('<BBH', 1, 255, 1) + bytes(2)).hex(),
        json.dumps(dict(type='audio.end', stream=255), separators=(',', ':')),
        (struct.pack('<BBH', 1, 255, 65535) + bytes.fromhex('0080ff7f')).hex(),
        (struct.pack('<BBH', 1, 255, 0) + bytes.fromhex('0100')).hex(),
        json.dumps(dict(type='audio.cancel', stream=255), separators=(',', ':')),
    ]
    if result != expected:
        raise RuntimeError('Java/Python synthetic wire mismatch')
    print('PASS independent Python struct/JSON comparison: 7 wire fixtures')
