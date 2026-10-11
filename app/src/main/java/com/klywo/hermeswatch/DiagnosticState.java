package com.klywo.hermeswatch;

/** Admission and lifecycle generations; retiring native work blocks replacement. */
public final class DiagnosticState {
    private final AdmissionGate gate;
    private AdmissionGate.Lease lease;
    public DiagnosticState() { this(new AdmissionGate()); }
    public DiagnosticState(AdmissionGate gate) { this.gate = gate; }
    private boolean active;
    private boolean busy;
    private long sequence;
    private long worker = -1;
    private long valid = -1;
    public synchronized void resume() { active = true; }
    public synchronized void pause() { active = false; busy = false; valid = -1; }
    public synchronized boolean available() { return active && worker == -1 && gate.available(); }
    public synchronized boolean busy() { return busy; }
    public synchronized long begin() {
        if (!available()) return -1;
        lease = gate.acquire();
        if (lease == null) return -1;
        worker = valid = ++sequence; busy = true; return valid;
    }
    public synchronized boolean current(long token) { return active && busy && valid == token; }
    public synchronized boolean cancel(long token) {
        if (!current(token)) return false;
        valid = -1; busy = false; return true;
    }
    public synchronized boolean complete(long token) {
        boolean accepted = current(token);
        if (accepted) { busy = false; valid = -1; }
        retired(token); return accepted;
    }
    public synchronized void retired(long token) {
        if (worker == token) {
            worker = -1;
            lease.retire();
            lease = null;
        }
    }
}
