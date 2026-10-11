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
    long queuedBytes();
    void close(int code, String reason);
    void cancel();
    /** Completion only after all native DNS/I/O and cleanup have returned. Never a cancel acknowledgement.
     * Unsupported implementations conservatively keep admission closed. Register before connect. */
    default void whenRetired(Runnable completion) { }

}
