package com.klywo.hermeswatch.transport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Compatibility name only: uses bounded raw RFC 6455 framing, not OkHttp WebSocket. */
public final class OkHttpWssTransport implements HgTransport {
    interface Connection {
        void open(URI endpoint) throws IOException;
        InputStream input() throws IOException;
        OutputStream output() throws IOException;
        void setReadTimeout(int millis) throws IOException;
        void close();
    }
    private static final ScheduledExecutorService DEADLINES=Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t=new Thread(r,"hg-wss-deadline"); t.setDaemon(true); return t;
    });
    private final Connection connection;
    private final SecureRandom random=new SecureRandom();
    private final ArrayBlockingQueue<Write> writes=new ArrayBlockingQueue<>(32);
    private Listener listener;
    private BoundedWssFrames frames;
    private Thread writer;
    private boolean used,stopped,opened,closing;
    private long pending;
    private boolean binaryAllowed;
    private long uploadEpoch;
    private int workers;
    private boolean cleanupComplete, retirementDelivered;
    private Runnable retirement;
    @Override public void whenRetired(Runnable completion) {
        synchronized(this) {
            if(retirement!=null) throw new IllegalStateException("Retirement observer already registered");
            retirement=Objects.requireNonNull(completion);
        }
        notifyRetirement();
    }
    private void notifyRetirement() {
        Runnable completion;
        synchronized(this) {
            if(!stopped || !cleanupComplete || workers!=0 || retirementDelivered || retirement==null) return;
            retirementDelivered=true; completion=retirement;
        }
        completion.run();
    }
    private void workerRetired() {
        synchronized(this) { workers--; }
        notifyRetirement();
    }

    private ScheduledFuture<?> connectDeadline,closeDeadline;
    private static final class Write {
        final int opcode; final byte[] payload; final boolean finish;
        final java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);
        boolean delivered, uploadWrite, closeControl; long epoch;
        Write(int opcode,byte[] payload,boolean finish) { this.opcode=opcode; this.payload=payload; this.finish=finish; }
    }
    public OkHttpWssTransport() { this(new PrivateWssSocket()); }
    OkHttpWssTransport(Connection connection) { this.connection=Objects.requireNonNull(connection); }
    @Override public void connect(String url,Listener target) {
        URI endpoint=PrivateWssEndpoint.validate(url); Objects.requireNonNull(target);
        synchronized(this) {
            if(used) throw new IllegalStateException("Single-use transport");
            used=true; listener=target;
            if(stopped) return;
            connectDeadline=DEADLINES.schedule(() -> finish(true),10000,TimeUnit.MILLISECONDS);
            // Reserve before publication/start so cancellation cannot report retirement early.
            workers++;
            Thread reader=new Thread(() -> receive(endpoint),"hg-wss-reader"); reader.setDaemon(true);
            try { reader.start(); }
            catch(RuntimeException | Error failure) { workers--; finish(true); throw failure; }
        }
    }
    private void receive(URI endpoint) {
        try {
            connection.open(endpoint);
            connection.setReadTimeout(10000);
            InputStream input=connection.input(); OutputStream output=connection.output();
            BoundedWssHandshake.upgrade(endpoint,input,output,random);
            // The pinned hub advertises a 20s heartbeat; setup inactivity is too short.
            // Keep the separate 30s absolute receive deadline, even during partial frames.
            connection.setReadTimeout(30000);
            synchronized(this) {
                if(stopped || closing) return;
                frames=new BoundedWssFrames(input,output,random,binaryAllowed);
                opened=true; connectDeadline.cancel(false);
                workers++;
                writer=new Thread(this::writeLoop,"hg-wss-writer"); writer.setDaemon(true);
                try { writer.start(); }
                catch(RuntimeException | Error failure) { workers--; throw failure; }
            }
            listener.onOpen();
            while(active()) {
                // Absolute event deadline also bounds slow fragments/control floods and pong writes.
                ScheduledFuture<?> readDeadline=DEADLINES.schedule(() -> finish(true),30000,TimeUnit.MILLISECONDS);
                BoundedWssFrames.Event event;
                try { event=frames.read(); }
                finally { readDeadline.cancel(false); }
                if(event.close!=null) { peerClose(event.close); return; }
                if(deliverable()) { if(event.binary!=null) listener.onBinary(event.binary); else listener.onText(event.text); }
            }
        } catch(IOException | RuntimeException e) { finish(true); }
        finally { workerRetired(); }
    }
    private synchronized boolean active() { return !stopped; }
    private synchronized boolean deliverable() { return opened && !closing && !stopped; }
    private void peerClose(byte[] payload) {
        synchronized(this) {
            if(stopped) return;
            if(closing) { /* Already sent/scheduled close: no second close frame. */ }
            else {
                closing=true; startCloseDeadline();
                if(writes.offer(new Write(8,payload,true))) return;
            }
        }
        finish(false);
    }
    private void writeLoop() {
        try {
            while(active()) {
                Write write=writes.take();
                try {
                    synchronized(this) {
                        if((write.opcode==1 || write.opcode==2) &&
                                (stopped || (!write.closeControl && !deliverable()) ||
                                 (write.uploadWrite && write.epoch!=uploadEpoch))) continue;
                    }
                    ScheduledFuture<?> deadline=DEADLINES.schedule(() -> finish(true),5000,TimeUnit.MILLISECONDS);
                    try { frames.write(write.opcode,write.payload,0,write.payload.length); write.delivered=true; }
                    finally { deadline.cancel(false); }
                    if(write.finish) { finish(false); return; }
                } finally {
                    synchronized(this) {
                        if(write.opcode==1 || write.opcode==2) pending-=write.payload.length;
                        notifyAll();
                    }
                    write.done.countDown();
                }
            }
        } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
        catch(IOException | RuntimeException e) { finish(true); }
        finally { workerRetired(); }
    }
    @Override public boolean send(String text) {
        byte[] payload;
        synchronized(this) {
            if(!opened || stopped || closing) return false;
            try { payload=BoundedWssFrames.encode(text); } catch(IOException e) { return false; }
            if(pending>HgSession.MAX_QUEUED_BYTES-payload.length || !writes.offer(new Write(1,payload,false))) return false;
            pending+=payload.length; return true;
        }
    }
    @Override public synchronized void allowBinary(boolean enabled) {
        if(used) throw new IllegalStateException("Capabilities fixed before connect"); binaryAllowed=enabled;
    }
    @Override public boolean sendAndWait(String text,long timeoutMillis) {
        try { return enqueueAndWait(1,BoundedWssFrames.encode(text),timeoutMillis); }
        catch(IOException e) { return false; }
    }
    @Override public boolean sendBinaryAndWait(byte[] bytes,long timeoutMillis) {
        try { BoundedWssFrames.validateAudio(bytes,bytes==null?0:bytes.length); }
        catch(IOException e) { return false; }
        synchronized(this) { if(!binaryAllowed) return false; }
        return enqueueAndWait(2,bytes.clone(),timeoutMillis);
    }
    private boolean enqueueAndWait(int opcode,byte[] payload,long timeoutMillis) {
        if(timeoutMillis<=0 || timeoutMillis>15000) return false;
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        Write write=new Write(opcode,payload,false);
        synchronized(this) {
            write.uploadWrite=true; write.epoch=uploadEpoch;
            while(opened && !stopped && !closing && write.epoch==uploadEpoch) {
                if(Thread.currentThread().isInterrupted()) return false;
                if(pending<=HgSession.MAX_QUEUED_BYTES-4096-payload.length && writes.remainingCapacity()>2) {
                    writes.add(write); pending+=payload.length; break;
                }
                long remaining=deadline-System.nanoTime(); if(remaining<=0) return false;
                try { TimeUnit.NANOSECONDS.timedWait(this,remaining); }
                catch(InterruptedException e) { Thread.currentThread().interrupt(); return false; }
            }
            if(!opened || stopped || closing || write.epoch!=uploadEpoch) return false;
        }
        try {
            long remaining=deadline-System.nanoTime();
            if(remaining>0 && write.done.await(remaining,TimeUnit.NANOSECONDS)) return write.delivered;
        } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
        synchronized(this) {
            if(writes.remove(write)) { pending-=payload.length; write.done.countDown(); notifyAll(); }
        }
        return false;
    }
    @Override public synchronized void dropPendingBinary() {
        uploadEpoch++;
        for(Write write:writes.toArray(new Write[0])) if(write.uploadWrite && writes.remove(write)) {
            pending-=write.payload.length; write.done.countDown();
        }
        notifyAll();
    }
    private void discardWrites() {
        Write write; while((write=writes.poll())!=null) {
            if(write.opcode==1 || write.opcode==2) pending-=write.payload.length;
            write.done.countDown();
        }
    }
    @Override public synchronized long queuedBytes() { return stopped?0:pending; }
    @Override public boolean closeAfterControls(String first, String second) {
        final Write audioCancel, cancel;
        try {
            audioCancel=new Write(1,BoundedWssFrames.encode(first),false);
            cancel=new Write(1,BoundedWssFrames.encode(second),false);
        } catch(IOException e) { cancel(); return false; }
        synchronized(this) {
            if(!opened || stopped || closing) return false;
            // Atomically stop upload admission and reserve the ordered terminal batch.
            // Only a native write already executing may precede these controls.
            uploadEpoch++;
            discardWrites();
            closing=true;
            audioCancel.closeControl=cancel.closeControl=true;
            writes.add(audioCancel); writes.add(cancel);
            pending+=audioCancel.payload.length+cancel.payload.length;
            writes.add(new Write(8,new byte[]{3,(byte)232},false));
            startCloseDeadline();
            notifyAll();
            return true;
        }
    }
    @Override public void close(int code,String reason) {
        byte[] text;
        try {
            if(!BoundedWssFrames.validCloseCode(code)) throw new IOException("Invalid close code");
            text=BoundedWssFrames.encode(reason==null?"":reason);
            if(text.length>123) throw new IOException("Close reason too long");
        } catch(IOException e) { cancel(); return; }
        byte[] payload=new byte[text.length+2]; payload[0]=(byte)(code>>>8); payload[1]=(byte)code;
        System.arraycopy(text,0,payload,2,text.length);
        synchronized(this) {
            if(stopped || closing) return;
            closing=true;
            if(opened) {
                startCloseDeadline();
                if(writes.offer(new Write(8,payload,false))) return;
            }
        }
        finish(false);
    }
    private void startCloseDeadline() {
        closeDeadline=DEADLINES.schedule(() -> finish(false),HgSession.CLOSE_MS,TimeUnit.MILLISECONDS);
    }
    private void finish(boolean failure) {
        Listener target;
        synchronized(this) {
            if(stopped) return;
            stopped=true; opened=false; target=listener;
            if(connectDeadline!=null) connectDeadline.cancel(false);
            if(closeDeadline!=null) closeDeadline.cancel(false);
            discardWrites(); notifyAll(); if(writer!=null) writer.interrupt();
        }
        try {
            connection.close();
            if(target!=null) { if(failure) target.onFailure(); else target.onClosed(); }
        } finally {
            synchronized(this) { cleanupComplete=true; }
            notifyRetirement();
        }
    }
    @Override public void cancel() {
        synchronized(this) {
            if(stopped) return;
            stopped=true; opened=false; discardWrites(); notifyAll();
            if(connectDeadline!=null) connectDeadline.cancel(false);
            if(closeDeadline!=null) closeDeadline.cancel(false);
            if(writer!=null) writer.interrupt();
        }
        try { connection.close(); }
        finally {
            synchronized(this) { cleanupComplete=true; }
            notifyRetirement();
        }
    }
}
