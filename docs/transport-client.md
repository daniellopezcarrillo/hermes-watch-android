# Transport client: text-turn correlation

## Executable reference

Verified against `styles01/hermes-watch` commit
`0764589a5c05c50ab4e48741b39b4675f6ec38f6`, specifically executable
`gateway/adapter.py` and `gateway/hub.py`, not just the protocol examples:

- Adapter `_message_id(session, msg_id)` constructs `session.session_id:msg_id`.
- `_event` uses that message ID for an inbound text request.
- `on_processing_start` sends that ID in `turn.start` and stores it in `_turns`.
- `send`, `send_draft`, and `edit_message` tag replies/deltas with the stored turn.
- `on_processing_complete` sends the event message ID in `turn.end`.
- Hub `welcome.session` exposes this connection's `session_id`; its turn/reply
  serializers preserve the adapter-provided identifier.

## Session invariant

`HgSession.sendText` returns the locally generated `requestId` and sends it as
`text.id`. It is **not** the server turn ID. Only one request may be active.

The first accepted `turn.start` binds a separate `serverTurn` once, requiring
exactly `welcome.session + ":" + requestId`. This checks the complete request
suffix and the authenticated connection's session prefix; arbitrary `endsWith`
matching, nested/ambiguous prefixes, empty prefixes, stale-session prefixes, and
partial request suffixes do not bind. Raw request IDs are deliberately rejected:
no executable evidence at the pinned adapter justifies raw-ID compatibility.

Before binding, replies/deltas/transcripts/end events cannot display content or
release single-flight admission, even if their identifier looks correct. After
binding, every turn event must equal the complete `serverTurn`; suffix matching
is never used again. Wrong or untagged events and unsolicited/stale turns are
ignored in READY. An exact duplicate start fails closed rather than rebinding.
The pinned hub emits untagged transcripts; this text-only slice ignores them
because correlation cannot be established (voice integration is not enabled).

An exact bound `turn.end` clears both identifiers before notifying the observer,
so a new request can be submitted. Cancel, turn timeout, unpair, disconnect,
failure, transport retirement, and heartbeat failure also clear both identifiers.
The timeout is measured from sending the request, not from `turn.start`. Fresh
request UUIDs ensure replay of a completed/cancelled/timed-out turn cannot bind
the next request. Terminal sessions remain single-use and ignore late callbacks.

## Focused JVM verification

Using the existing ignored local Java 17 toolchain and existing test-only jars:

```sh
mkdir -p .tooling/session-correlation-tests
javac -cp .tooling/json.jar:.tooling/junit.jar:.tooling/hamcrest.jar \
  -d .tooling/session-correlation-tests \
  app/src/main/java/com/klywo/hermeswatch/protocol/*.java \
  app/src/main/java/com/klywo/hermeswatch/transport/HgTransport.java \
  app/src/main/java/com/klywo/hermeswatch/transport/PrivateWssEndpoint.java \
  app/src/main/java/com/klywo/hermeswatch/transport/HgSession.java \
  app/src/test/java/com/klywo/hermeswatch/transport/HgSessionTest.java
java -cp .tooling/session-correlation-tests:.tooling/json.jar:.tooling/junit.jar:.tooling/hamcrest.jar \
  org.junit.runner.JUnitCore com.klywo.hermeswatch.transport.HgSessionTest
```

These are deterministic fake-transport JVM tests, not live server or watch
verification. No socket implementation, runtime dependency, or UI integration
is changed by the correlation fix.
