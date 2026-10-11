"""TEST ONLY pipe adapter. Executes unmodified pinned DeviceHub/DeviceStore.

The fake delegate is deterministic test scaffolding, NOT Hermes/model/voice.
No TCP listener, gateway adapter, model, TTS or external service is started.
"""
import asyncio
import contextlib
import json
import pathlib
import subprocess
import sys

# Fail before argument parsing/importing the upstream hub: never emit evidence
# from an interpreter that has removed the validation assertions.
if not __debug__ or sys.flags.optimize:
    raise SystemExit("Optimized Python is unsupported: validation requires assertions")

PIN = "0764589a5c05c50ab4e48741b39b4675f6ec38f6"
upstream = pathlib.Path(sys.argv[1]).resolve()
assert subprocess.check_output(["git", "-C", str(upstream), "rev-parse", "HEAD"], text=True).strip() == PIN, "Wrong upstream commit"
assert not subprocess.check_output(["git", "-C", str(upstream), "status", "--porcelain", "--", "gateway"], text=True).strip(), "Pinned gateway has local edits"
sys.path.insert(0, str(upstream))
from gateway.hub import DeviceHub, HubDelegate  # noqa: E402
from gateway.store import DeviceStore  # noqa: E402
from gateway import protocol  # noqa: E402


def emit(obj):
    print(json.dumps(obj, ensure_ascii=False, separators=(",", ":")), flush=True)


class PipeWebSocket:
    """Minimal test-only WS-shaped pipe, not a WebSocket implementation."""
    remote_address = ("127.0.0.1", 0)

    def __init__(self):
        self.incoming = asyncio.Queue()
        self.inbound = []
        self.outbound = []

    async def send(self, raw):
        assert isinstance(raw, str), "Text-only harness"
        obj = json.loads(raw)
        self.outbound.append(obj)
        emit({"wire": obj})

    async def recv(self):
        raw = await self.incoming.get()
        if raw is None:
            raise EOFError("Pipe retired")
        self.inbound.append(json.loads(raw))
        return raw

    def __aiter__(self):
        return self

    async def __anext__(self):
        raw = await self.incoming.get()
        if raw is None:
            raise StopAsyncIteration
        self.inbound.append(json.loads(raw))
        return raw

    async def close(self, code=1000, reason=""):
        await self.incoming.put(None)


class TestOnlyFakeDelegate(HubDelegate):
    """NOT the Hermes adapter or model; fixed fixtures and manual approval."""
    def __init__(self, store):
        self.store = store
        self.texts = []
        self.cancels = 0
        self.current = None

    async def is_paired(self, session):
        return not self.store.devices()[session.device_id]["pending"]

    async def on_ready(self, session):
        if not session.paired:
            self.store.remember_pairing(session.device_id, "TEST-ONLY-PAIR-42", "test-only manual operator approval", 60)
            code, command = self.store.pairing_for(session.device_id)
            await session.send_pairing(code, command)

    async def on_text(self, session, msg_id, text):
        self.texts.append((msg_id, text))
        turn = session.session_id + ":" + msg_id
        self.current = turn
        session.turn_open = turn
        if text == "hold for cancellation":
            await session.turn_start(turn)
            return
        assert text == "fixture request — café"
        # Actual hub serialization of deliberately unrelated correlation frames.
        await session.turn_start("wrong-session:" + msg_id)
        await session.send_reply("UNRELATED fixture must be suppressed", turn="wrong-session:" + msg_id)
        await session.turn_end("wrong-session:" + msg_id)
        await session.turn_start(turn)
        await session.send_reply("UNRELATED suffix fixture", turn=session.session_id + ":wrong-request")
        await session.send_reply("TEST-ONLY fake delegate fixture reply — café", turn=turn)
        await session.turn_end(turn)
        session.turn_open = None

    async def on_cancel(self, session):
        self.cancels += 1
        assert self.current == session.turn_open
        # These are real hub sends, but HgSession must suppress the stale turn.
        await session.send_reply("LATE canceled fixture", turn=self.current)
        await session.turn_end(self.current, outcome="cancelled")
        session.turn_open = None
        await session.send_status("cancel-observed-by-test-only-delegate")


async def main():
    store = DeviceStore(pathlib.Path(sys.argv[2]))
    delegate = TestOnlyFakeDelegate(store)
    # Never call start(): no listener. _handle executes the REAL authentication,
    # enrollment, receive loop, routing, heartbeat and session cleanup over pipes.
    hub = DeviceHub(store, delegate, host="127.0.0.1", port=0, heartbeat_s=5)
    ws = PipeWebSocket()
    handler = asyncio.create_task(hub._handle(ws))
    finished = False
    try:
        while True:
            raw = await asyncio.to_thread(sys.stdin.readline)
            if not raw:
                break
            envelope = json.loads(raw)
            if "wire" in envelope:
                await ws.incoming.put(json.dumps(envelope["wire"], ensure_ascii=False))
                continue
            control = envelope["control"]
            if control == "pair":
                assert len(hub.sessions) == 1
                session = next(iter(hub.sessions.values()))
                assert not session.paired and session.device_id in store.pending()
                assert store.key_for(session.device_id) is not None
                pairing = store.pairing_for(session.device_id)
                assert pairing and envelope["code"] == pairing[0]
                # Explicit operator action in harness, not automatic on_ready.
                await session.set_paired(True)
                store.clear_pairing(session.device_id)
                fresh = DeviceStore(store.path.parent)
                assert not fresh.devices()[session.device_id]["pending"]
                assert fresh.pairing_for(session.device_id) is None
                emit({"control": "paired-store-verified"})
            elif control == "finish":
                assert len(delegate.texts) == 2 and delegate.cancels == 1
                hellos = [o for o in ws.inbound if o["type"] == "hello"]
                assert len(hellos) == 1
                device_id = hellos[0]["device_id"]
                fresh = DeviceStore(store.path.parent)
                assert fresh.key_for(device_id) is not None
                assert protocol.device_id_for_key(fresh.key_for(device_id)) == device_id
                assert not fresh.devices()[device_id]["pending"]
                pings = [o for o in ws.outbound if o["type"] == "ping"]
                pongs = [o for o in ws.inbound if o["type"] == "pong"]
                assert pings and any(p["ts"] == pings[0]["ts"] for p in pongs)
                assert len([o for o in ws.inbound if o["type"] == "cancel"]) == 1
                await ws.close()
                await asyncio.wait_for(handler, 5)
                await hub.stop()
                assert not hub.sessions and not hub._tasks
                # Summary contains no key, MAC, nonce or raw enrollment payload.
                emit({"control": "verified", "pinned_commit": PIN, "delegate": "TEST-ONLY fake; NOT Hermes", "texts": len(delegate.texts), "cancels": delegate.cancels, "heartbeat_pongs": len(pongs), "store_confirmed": True, "cleanup": True})
                finished = True
                break
            elif control == "close":
                await ws.close()
            else:
                raise AssertionError("Unknown test control")
        assert finished, "Java exited before verification"
    finally:
        await ws.close()
        with contextlib.suppress(Exception):
            await asyncio.wait_for(handler, 5)
        await hub.stop()


if __name__ == "__main__":
    asyncio.run(main())
