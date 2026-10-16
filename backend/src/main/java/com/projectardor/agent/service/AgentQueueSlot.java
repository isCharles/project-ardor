package com.projectardor.agent.service;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Owns an already acquired queue permit; user confirmation waits do not occupy it. */
final class AgentQueueSlot implements AutoCloseable {
    private final Semaphore slots;
    private boolean held = true;
    private boolean closed;
    AgentQueueSlot(Semaphore slots) { this.slots = slots; }
    synchronized void pause() {
        if (!closed && held) { held = false; slots.release(); }
    }
    void resume() {
        while (true) {
            synchronized (this) { if (closed || held) return; }
            try {
                if (!slots.tryAcquire(100, TimeUnit.MILLISECONDS)) continue;
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CancellationException("Queue resume interrupted");
            }
            synchronized (this) {
                if (closed || held) slots.release(); else held = true;
                return;
            }
        }
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (held) { held = false; slots.release(); }
    }
}
