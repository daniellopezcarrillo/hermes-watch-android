package com.klywo.hermeswatch;

/** Process admission only: no Activity, callbacks, executors or native resource references. */
public final class AdmissionGate {
    private static final AdmissionGate PROCESS = new AdmissionGate();
    private Lease current;

    public static AdmissionGate processWide() { return PROCESS; }
    public synchronized boolean available() { return current == null; }
    public synchronized Lease acquire() {
        if (current != null) return null;
        current = new Lease(this);
        return current;
    }
    private synchronized void release(Lease lease) {
        if (current == lease) current = null;
    }

    /** Identity, not an Activity-local generation, determines ownership. */
    public static final class Lease {
        private final AdmissionGate gate;
        private Lease(AdmissionGate gate) { this.gate = gate; }
        public void retire() { gate.release(this); }
    }
}
