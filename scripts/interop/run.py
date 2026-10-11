#!/usr/bin/env python3
"""Build/run isolated Java↔pinned Python hub tests. Writes only .tooling/."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

# Assertions below are evidence gates, not optional debug checks. Reject -O,
# -OO and PYTHONOPTIMIZE before filesystem, dependency or subprocess work.
if not __debug__ or sys.flags.optimize:
    raise SystemExit("Optimized Python is unsupported: validation requires assertions")

ROOT = Path(__file__).resolve().parents[2]
UPSTREAM = Path(os.environ.get("HG_UPSTREAM", ROOT.parent / ".repo-research/hermes-watch")).resolve()
PIN = "0764589a5c05c50ab4e48741b39b4675f6ec38f6"


def run(command):
    print("+ " + " ".join(map(str, command)), flush=True)
    subprocess.run(list(map(str, command)), cwd=ROOT, check=True, timeout=90)


def main():
    os.chdir(ROOT)
    assert subprocess.check_output(["git", "-C", str(UPSTREAM), "rev-parse", "HEAD"], text=True).strip() == PIN, "Upstream pin mismatch"
    assert subprocess.run(["git", "check-ignore", "-q", ".tooling"], check=False).returncode == 0, ".tooling must be ignored"
    tooling = ROOT / ".tooling"
    tooling.mkdir(exist_ok=True)
    python = tooling / "interop-venv/bin/python"
    if not python.exists():
        run(["uv", "venv", tooling / "interop-venv"])
    run(["uv", "pip", "install", "--python", python, "websockets==15.0.1"])
    json_jar = Path(os.environ.get("JSON_JAR", tooling / "json.jar")).resolve()
    assert json_jar.is_file(), "Provide existing org.json jar via JSON_JAR (no app dependency changes)"
    java = os.environ.get("JAVA_HOME")
    java_bin = Path(java) / "bin/java" if java else shutil.which("java")
    javac_bin = Path(java) / "bin/javac" if java else shutil.which("javac")
    assert java_bin and javac_bin, "JDK required (JAVA_HOME or PATH)"
    classes = tooling / "interop-classes"
    classes.mkdir(exist_ok=True)
    src = ROOT / "app/src/main/java/com/klywo/hermeswatch"
    # HgSession owns pure voice state even when this harness selects text-only mode.
    sources = sorted((src / "protocol").glob("*.java")) + sorted((src / "voice").glob("*.java")) + [src / "transport" / (name + ".java") for name in ["HgTransport", "HgSession", "PrivateWssEndpoint"]] + [ROOT / "scripts/interop/InteropHarness.java"]
    run([javac_bin, "-cp", json_jar, "-d", classes, *sources])
    # New store per run, shared ONLY by enrollment and the subsequent HMAC reconnect.
    with tempfile.TemporaryDirectory(prefix="interop-store-", dir=tooling) as store:
        for mode in ["enroll", "reconnect"]:
            run([java_bin, "-cp", str(classes) + os.pathsep + str(json_jar), "InteropHarness", python, ROOT / "scripts/interop/hub_bridge.py", UPSTREAM, store, mode])
    print("PASS isolated interoperability: 2 scenarios; no listener/deployment/production edits", flush=True)


if __name__ == "__main__":
    main()
