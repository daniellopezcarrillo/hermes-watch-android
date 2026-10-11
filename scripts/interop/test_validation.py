"""Regression: optimized Python must never issue interoperability evidence."""
import os
from pathlib import Path
import subprocess
import sys
import unittest


class ValidationTest(unittest.TestCase):
    def test_optimized_python_rejected_before_any_work(self):
        for name in ("run.py", "hub_bridge.py"):
            with self.subTest(script=name):
                result = subprocess.run(
                    [sys.executable, str(Path(__file__).with_name(name))],
                    env={**os.environ, "PYTHONOPTIMIZE": "1"},
                    capture_output=True, text=True, timeout=10,
                )
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("Optimized Python is unsupported", result.stderr)
                self.assertNotIn("PASS", result.stdout)
                self.assertNotIn('"control":"verified"', result.stdout)
                self.assertEqual(result.stdout, "")


if __name__ == "__main__":
    unittest.main()
