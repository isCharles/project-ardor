package com.projectardor.assistant.execution.service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AgentQueueSlotTests {
    @Test void waitingApprovalsReleaseBothSlotsForOtherWork() throws Exception {
        var permits = new Semaphore(2);
        permits.acquire(2);
        var first = new AgentQueueSlot(permits);
        var second = new AgentQueueSlot(permits);
        first.pause(); second.pause(); first.pause();
        assertThat(permits.availablePermits()).isEqualTo(2);
        assertThat(permits.tryAcquire()).isTrue();
        permits.release();
        first.resume(); second.resume();
        assertThat(permits.availablePermits()).isZero();
        first.close(); second.close(); first.close();
        assertThat(permits.availablePermits()).isEqualTo(2);
    }

    @Test void closingWhileResumeWaitsDoesNotLeakOrDoubleRelease() throws Exception {
        var permits = new Semaphore(0);
        var slot = new AgentQueueSlot(permits);
        slot.pause();
        permits.acquire(); // Another task holds the available permit.
        var resumed = CompletableFuture.runAsync(slot::resume);
        slot.close();
        resumed.get(2, TimeUnit.SECONDS);
        assertThat(permits.availablePermits()).isZero();
        permits.release();
        slot.resume(); slot.close();
        assertThat(permits.availablePermits()).isEqualTo(1);
    }
}
