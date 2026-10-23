package com.projectardor.assistant.execution.service;

import java.time.Duration;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Monotonic deadlines and a fence against late business calls; no provider reasoning is inferred. */
final class AgentStreamLease {
    private final LongSupplier clock;
    private final long startedAt;
    private long lastData;
    private boolean waitingForTool;
    private boolean warned;
    private boolean stopped;
    private boolean releaseRegistered;
    private int activeTools;
    private Runnable cancel;
    private Runnable transportCancel;
    private Runnable synchronousCancel;
    private Runnable release;
    AgentStreamLease() { this(System::nanoTime); }
    AgentStreamLease(LongSupplier clock) { this.clock = clock; this.startedAt = clock.getAsLong(); this.lastData = startedAt; }

    synchronized void data() { if (!stopped) { lastData = clock.getAsLong(); warned = false; } }
    synchronized boolean toolWaiting() {
        if (stopped) return false;
        waitingForTool = true;
        cancel = null; // The prior model round has ended; do not cancel a stale handle.
        transportCancel = null;
        return true;
    }
    synchronized boolean toolFinished() {
        if (stopped) return false;
        waitingForTool = false; lastData = clock.getAsLong(); warned = false;
        return true;
    }
    synchronized boolean expired(Duration silence, Duration total) {
        return !stopped && (clock.getAsLong() - startedAt >= total.toNanos()
                || !waitingForTool && clock.getAsLong() - lastData >= silence.toNanos());
    }
    synchronized boolean totalExpired(Duration total) { return clock.getAsLong() - startedAt >= total.toNanos(); }
    synchronized boolean warnSilence() {
        if (stopped || waitingForTool || warned || clock.getAsLong() - lastData < Duration.ofSeconds(30).toNanos()) return false;
        warned = true; return true;
    }
    boolean attachCancellation(Runnable action) {
        synchronized (this) { if (!stopped) { cancel = action; return true; } }
        cancelSafely(action);
        return false;
    }
    boolean attachTransportCancellation(Runnable action) {
        synchronized (this) { if (!stopped) { transportCancel = action; return true; } }
        cancelSafely(action);
        return false;
    }
    synchronized boolean stopped() { return stopped; }
    /** Synchronous graph phases still obey the same stop/deadline fence. */
    <T> T modelCall(Supplier<T> call) {
        var task = new java.util.concurrent.FutureTask<T>(() -> {
            if (stopped()) throw new java.util.concurrent.CancellationException("Agent run stopped");
            return call.get();
        });
        Runnable cancellation = () -> task.cancel(true);
        synchronized (this) {
            if (stopped) throw new java.util.concurrent.CancellationException("Agent run stopped");
            synchronousCancel = cancellation;
        }
        Thread.ofVirtual().name("ardor-graph-model").start(task);
        try {
            T value = task.get();
            if (stopped()) throw new java.util.concurrent.CancellationException("Agent run stopped");
            data();
            return value;
        } catch (InterruptedException interrupted) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("Agent run interrupted");
        } catch (java.util.concurrent.ExecutionException failed) {
            if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Graph model call failed", failed.getCause());
        } finally {
            synchronized (this) { if (synchronousCancel == cancellation) synchronousCancel = null; }
        }
    }
    String execute(Supplier<String> businessCall) {
        synchronized (this) {
            if (stopped) return "{\"status\":\"RUN_STOPPED\",\"message\":\"本轮已经停止，没有执行工具\"}";
            activeTools++;
        }
        try { return businessCall.get(); }
        finally {
            Runnable ready = null;
            synchronized (this) {
                activeTools--;
                if (stopped && activeTools == 0) { ready = release; release = null; }
            }
            if (ready != null) ready.run();
        }
    }
    void stop(Runnable whenIdle) {
        Runnable cancellation;
        Runnable transport;
        Runnable synchronous;
        Runnable ready = null;
        synchronized (this) {
            if (releaseRegistered) return;
            releaseRegistered = true;
            stopped = true; cancellation = cancel; cancel = null;
            transport = transportCancel; transportCancel = null;
            synchronous = synchronousCancel; synchronousCancel = null;
            release = whenIdle;
            if (activeTools == 0) { ready = release; release = null; }
        }
        try { cancelSafely(transport); cancelSafely(cancellation); cancelSafely(synchronous); }
        finally { if (ready != null) ready.run(); }
    }
    void fence() {
        Runnable cancellation;
        Runnable transport;
        Runnable synchronous;
        synchronized (this) {
            if (stopped) return;
            stopped = true; cancellation = cancel; cancel = null;
            transport = transportCancel; transportCancel = null;
            synchronous = synchronousCancel; synchronousCancel = null;
        }
        cancelSafely(transport);
        cancelSafely(cancellation);
        cancelSafely(synchronous);
    }
    private void cancelSafely(Runnable action) {
        if (action == null) return;
        try { action.run(); }
        catch (RuntimeException ignored) { /* Local execution stays fenced even if transport cancellation fails. */ }
    }
}
