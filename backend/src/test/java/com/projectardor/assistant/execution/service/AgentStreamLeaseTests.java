package com.projectardor.assistant.execution.service;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AgentStreamLeaseTests {
    @Test void silenceIsMeasuredFromRealDataNotHeartbeatOrToolWait() {
        AtomicLong nanos = new AtomicLong();
        AgentStreamLease lease = new AgentStreamLease(nanos::get);
        nanos.set(Duration.ofSeconds(31).toNanos());
        assertThat(lease.warnSilence()).isTrue();
        assertThat(lease.warnSilence()).isFalse();
        lease.data();
        nanos.set(Duration.ofSeconds(190).toNanos());
        assertThat(lease.expired(Duration.ofSeconds(180), Duration.ofMinutes(10))).isFalse();
        lease.toolWaiting();
        nanos.set(Duration.ofSeconds(400).toNanos());
        assertThat(lease.expired(Duration.ofSeconds(180), Duration.ofMinutes(10))).isFalse();
        assertThat(lease.warnSilence()).isFalse();
        nanos.set(Duration.ofMinutes(10).toNanos());
        assertThat(lease.expired(Duration.ofSeconds(180), Duration.ofMinutes(10))).isTrue();
    }
    @Test void stoppedRunRejectsLateBusinessCallsAndCancelsLateHandles() {
        AgentStreamLease lease = new AgentStreamLease();
        AtomicInteger cancelled = new AtomicInteger();
        AtomicInteger released = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        lease.attachCancellation(cancelled::incrementAndGet);
        lease.fence();
        assertThat(lease.execute(() -> { calls.incrementAndGet(); return "bad"; })).contains("RUN_STOPPED");
        assertThat(lease.attachCancellation(cancelled::incrementAndGet)).isFalse();
        lease.stop(released::incrementAndGet);
        lease.stop(released::incrementAndGet);
        assertThat(calls).hasValue(0);
        assertThat(cancelled).hasValue(2);
        assertThat(released).hasValue(1);
    }
    @Test void activeBusinessWorkRetainsUserLockUntilItExits() throws Exception {
        AgentStreamLease lease = new AgentStreamLease();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch exit = new CountDownLatch(1);
        AtomicInteger released = new AtomicInteger();
        var task = CompletableFuture.supplyAsync(() -> lease.execute(() -> {
            entered.countDown();
            try { assertThat(exit.await(2, TimeUnit.SECONDS)).isTrue(); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
            return "already-started-result";
        }));
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            lease.fence();
            lease.stop(released::incrementAndGet);
            assertThat(released).hasValue(0);
            assertThat(lease.execute(() -> "must-not-start")).contains("RUN_STOPPED");
        } finally { exit.countDown(); }
        assertThat(task.get(2, TimeUnit.SECONDS)).isEqualTo("already-started-result");
        assertThat(released).hasValue(1);
    }
    @Test void transportCancellationFailureCannotReopenOrLeakTheLease() {
        AgentStreamLease lease = new AgentStreamLease();
        AtomicInteger released = new AtomicInteger();
        lease.attachCancellation(() -> { throw new IllegalStateException("Synthetic transport failure"); });
        lease.stop(released::incrementAndGet);
        assertThat(released).hasValue(1);
        assertThat(lease.toolWaiting()).isFalse();
        assertThat(lease.toolFinished()).isFalse();
    }
    @Test void failingBusinessCallStillReleasesOnlyAfterItsExit() {
        AgentStreamLease lease = new AgentStreamLease();
        AtomicInteger released = new AtomicInteger();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> lease.execute(() -> {
            lease.stop(released::incrementAndGet);
            assertThat(released).hasValue(0);
            throw new IllegalStateException("Synthetic business failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(released).hasValue(1);
        lease.stop(released::incrementAndGet);
        assertThat(released).hasValue(1);
    }
}
