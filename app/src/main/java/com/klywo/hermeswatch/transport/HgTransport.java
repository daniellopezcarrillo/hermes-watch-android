package com.klywo.hermeswatch.transport;

/** One connection per instance; callbacks may arrive on any thread. */
public interface HgTransport {
    interface Listener {
        void onOpen();
        void onText(String frame);
        void onBinary(byte[] frame);
        void onClosed();
        void onFailure();
    }
    void connect(String url, Listener listener);
    boolean send(String frame);
    /** Select before connect; default transports remain text-only. */
    default void allowBinary(boolean enabled) { if(enabled) throw new UnsupportedOperationException("Voice unsupported"); }
    /** Worker-only bounded delivery wait. False means not delivered; never call from UI. */
    default boolean sendAndWait(String frame,long timeoutMillis) { return false; }
    default boolean sendBinaryAndWait(byte[] frame,long timeoutMillis) { return false; }
    /** Invalidate queued/in-flight upload work before cancel controls. */
    default void dropPendingBinary() { }
    long queuedBytes();
    void close(int code, String reason);
    /** Nonblocking ordered control-write barrier followed by close, with a bounded deadline.
     * Acceptance is queue admission, not peer acknowledgement or native retirement. */
    default boolean closeAfterControls(String first, String second) {
        // Unsupported transports must not claim an ordered delivery barrier.
        cancel();
        return false;
    }
    void cancel();
    /** Completion only after all native DNS/I/O and cleanup have returned. Never a cancel acknowledgement.
     * Unsupported implementations conservatively keep admission closed. Register before connect. */
    default void whenRetired(Runnable completion) { }

}
