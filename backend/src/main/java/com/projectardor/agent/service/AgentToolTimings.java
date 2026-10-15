package com.projectardor.agent.service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** One run's measurements; no tool arguments or results are retained here. */
final class AgentToolTimings {
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Timing> calls = new ConcurrentHashMap<>();
    AgentToolTimings() { this(System::nanoTime); }
    AgentToolTimings(LongSupplier clock) { this.clock = clock; }
    long now() { return clock.getAsLong(); }
    void record(String id, long started, long approved, boolean executed) {
        if (id != null) calls.put(id, new Timing(millis(approved - started), executed ? millis(now() - approved) : 0, executed));
    }
    Timing remove(String id) { return id == null ? null : calls.remove(id); }
    private long millis(long nanos) { return Math.max(0, TimeUnit.NANOSECONDS.toMillis(nanos)); }
    record Timing(long approvalWaitMs, long businessMs, boolean executed) {}
}
