import com.klywo.hermeswatch.protocol.HgV1Codec;
import com.klywo.hermeswatch.transport.*;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** TEST ONLY: pipe transport to actual pinned Python DeviceHub; no network or TLS. */
public final class InteropHarness {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static final class Pipes implements HgTransport {
        final Process process;
        final PrintWriter output;
        final BlockingQueue<JSONObject> controls = new LinkedBlockingQueue<>();
        final List<JSONObject> received = new CopyOnWriteArrayList<>();
        final List<JSONObject> sent = new CopyOnWriteArrayList<>();
        Listener listener;
        Pipes(String python, String bridge, String upstream, String store) throws Exception {
            process = new ProcessBuilder(python, "-u", bridge, upstream, store).redirectError(ProcessBuilder.Redirect.INHERIT).start();
            output = new PrintWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8), true);
        }
        synchronized void control(String name, String code) {
            JSONObject obj = new JSONObject().put("control", name);
            if (code != null) obj.put("code", code);
            output.println(obj);
        }
        public void connect(String unused, Listener target) {
            listener = target;
            Thread reader = new Thread(() -> {
                try (BufferedReader input = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    for (String line; (line = input.readLine()) != null;) {
                        JSONObject envelope = new JSONObject(line);
                        if (envelope.has("wire")) {
                            JSONObject wire = envelope.getJSONObject("wire"); received.add(wire);
                            listener.onText(wire.toString());
                        } else controls.add(envelope);
                    }
                } catch (Exception error) { error.printStackTrace(); listener.onFailure(); }
            }, "interop-python-reader");
            reader.setDaemon(true); reader.start(); listener.onOpen();
        }
        public synchronized boolean send(String frame) {
            sent.add(new JSONObject(frame)); output.println(new JSONObject().put("wire", new JSONObject(frame))); return !output.checkError();
        }
        public long queuedBytes() { return 0; }
        public void close(int code, String reason) { control("close", null); }
        public void cancel() { process.destroy(); }
        JSONObject awaitControl(String name) throws Exception {
            JSONObject obj = controls.poll(12, TimeUnit.SECONDS);
            check(obj != null && name.equals(obj.optString("control")), "Expected control " + name + ", got " + obj); return obj;
        }
    }
    interface Condition { boolean test(); }
    static void await(Condition condition, String what) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (!condition.test() && System.nanoTime() < until) Thread.sleep(10);
        check(condition.test(), "Timed out: " + what);
    }
    static long count(List<JSONObject> frames, String type) { return frames.stream().filter(o -> type.equals(o.optString("type"))).count(); }
    public static void main(String[] args) throws Exception {
        boolean enrolling = args[4].equals("enroll");
        byte[] key = new byte[32]; Arrays.fill(key, (byte) 73); // Public TEST ONLY identity, not a deployed identity.
        Pipes pipes = new Pipes(args[0], args[1], args[2], args[3]);
        List<JSONObject> events = new CopyOnWriteArrayList<>();
        HgSession session = new HgSession(pipes, () -> key.clone(), () -> System.nanoTime()/1_000_000,
            (type, frame) -> { if (frame != null) events.add(frame); });
        try {
            // Syntactically approved private URI only: Pipes never resolves or connects to it.
            session.connect("wss://192.168.0.1/gadget", enrolling);
            if (enrolling) {
                await(() -> session.state() == HgSession.State.PENDING && count(events, "pairing") == 1, "enrollment/pairing");
                check(pipes.received.get(0).getBoolean("enrolled") == false, "Fresh challenge");
                check(pipes.sent.get(1).has("key") && !pipes.sent.get(1).has("mac"), "Explicit enrollment key");
                try { session.sendText("must not pass before operator approval"); throw new AssertionError("Pending text accepted"); }
                catch (IllegalStateException expected) { }
                JSONObject pairing = events.stream().filter(o -> "pairing".equals(o.optString("type"))).findFirst().get();
                pipes.control("pair", pairing.getString("code")); pipes.awaitControl("paired-store-verified");
            }
            await(() -> session.state() == HgSession.State.READY, "ready");
            if (!enrolling) {
                check(pipes.received.get(0).getBoolean("enrolled"), "Reconnect known challenge");
                check(pipes.sent.get(1).has("mac") && !pipes.sent.get(1).has("key"), "HMAC without key reenrollment");
            }
            String request = session.sendText("fixture request — café");
            await(() -> count(events, "turn.end") == 1, "start/reply/end");
            String prefix = pipes.received.stream().filter(o -> "welcome".equals(o.optString("type"))).findFirst().get().getString("session") + ":" + request;
            List<JSONObject> turns = new ArrayList<>();
            for (JSONObject e : events) if (Arrays.asList("turn.start", "reply", "turn.end").contains(e.optString("type"))) turns.add(e);
            check(turns.size() == 3, "Unrelated correlation frames must not be emitted: " + turns);
            check("turn.start".equals(turns.get(0).getString("type")) && "reply".equals(turns.get(1).getString("type")) && "turn.end".equals(turns.get(2).getString("type")), "Lifecycle order");
            for (JSONObject e : turns) check(prefix.equals(e.getString("turn")), "Exact prefixed correlation");
            check("TEST-ONLY fake delegate fixture reply — café".equals(turns.get(1).getString("text")), "Fixture not self echo");
            check("success".equals(turns.get(2).getString("outcome")), "Success outcome");
            await(() -> count(pipes.sent, "pong") >= 1, "actual hub heartbeat");
            JSONObject ping = pipes.received.stream().filter(o -> "ping".equals(o.optString("type"))).findFirst().get();
            JSONObject pong = pipes.sent.stream().filter(o -> "pong".equals(o.optString("type"))).findFirst().get();
            check(ping.getLong("ts") == pong.getLong("ts"), "Heartbeat timestamp preserved"); session.tick();
            session.sendText("hold for cancellation");
            await(() -> count(events, "turn.start") == 2, "held turn start");
            check(session.cancelTurn(), "Cancel transmitted");
            await(() -> events.stream().anyMatch(o -> "cancel-observed-by-test-only-delegate".equals(o.optString("text"))), "hub dispatches cancel");
            check(count(events, "reply") == 1 && count(events, "turn.end") == 1, "Late canceled reply/end ignored");
            check(!session.cancelTurn(), "Canceled turn cleared");
            check(session.state() == HgSession.State.READY, "Session survives heartbeat/cancel");
            pipes.control("finish", null); JSONObject report = pipes.awaitControl("verified");
            check(pipes.process.waitFor(12, TimeUnit.SECONDS) && pipes.process.exitValue() == 0, "Python assertions/exit");
            session.disconnect(); session.tick();
            System.out.println("PASS " + args[4] + ": enrollment/HMAC, operator pairing, prefixed lifecycle + wrong-correlation rejection, real heartbeat, cancel + stale suppression; " + report);
        } finally { if (pipes.process.isAlive()) pipes.process.destroyForcibly(); }
    }
}
