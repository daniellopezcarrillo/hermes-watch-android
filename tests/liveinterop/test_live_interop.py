"""Runs the cross-language fixture; skips cleanly when no local JDK/org.json is present."""
import subprocess
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))
import live_interop_fixture as fixture  # noqa: E402

_JAVA, _JAVAC = fixture.java_tools()
_AVAILABLE = _JAVA is not None and fixture.json_jar() is not None
_REASON = 'JDK and org.json jar required for the Java/Python cross-language fixture'


class LiveInteropTest(unittest.TestCase):
    @unittest.skipUnless(_AVAILABLE, _REASON)
    def test_challenge_matches_across_real_java_and_python(self):
        result = subprocess.run([sys.executable, str(fixture.__file__)],
                                capture_output=True, text=True, timeout=120)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('PASS cross-language live challenge', result.stdout)
        self.assertNotIn('Traceback', result.stderr)


if __name__ == '__main__':
    unittest.main()
