package com.projectardor.assistant.execution.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.projectardor.assistant.ReasoningDepth;
import com.projectardor.assistant.conversation.web.AgentMessageRequest;
import com.projectardor.assistant.execution.web.AgentStreamEvent;
import com.projectardor.common.web.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentMessageQueueWorkerTests {
    @Test void onlyProvenBusyBeforeRunCreationMayRequeue() {
        runFailure("AGENT_BUSY", true, false);
    }
    @Test void invalidOrUnknownStartupDoesNotLoopOrReplay() {
        runFailure("INVALID_INPUT", false, false);
    }
    @Test void thrownUnknownOutcomeIsInterruptedInsteadOfAutomaticallyRetried() {
        runFailure("UNKNOWN", false, true);
    }
    private void runFailure(String code, boolean requeue, boolean thrown) {
        var queue = mock(AgentMessageQueue.class);
        var service = mock(CareerAgentService.class);
        var runs = mock(AgentRunStore.class);
        UUID owner = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        var input = new AgentMessageRequest(UUID.randomUUID(), "Synthetic input", null, null, List.of(), id, ReasoningDepth.DEEP);
        var item = new AgentMessageQueue.Item(id, owner, "DISPATCHING", input, null, null, Instant.now());
        when(queue.claimNext()).thenReturn(Optional.of(item));
        when(runs.get(owner, id)).thenThrow(new ResourceNotFoundException("请求记录不存在"));
        doAnswer(call -> {
            if (thrown) throw new IllegalStateException("Unknown outcome");
            CareerAgentService.StreamSink sink = call.getArgument(9);
            sink.send(AgentStreamEvent.error(code, "Synthetic failure", false, 0));
            sink.complete();
            sink.complete(); // Duplicate transport callbacks cannot release slots or dispatch again.
            return null;
        }).when(service).chatStream(eq(owner), eq(input.conversationId()), eq(input.message()), isNull(), isNull(),
                eq(List.of()), eq(id), eq(ReasoningDepth.DEEP), isNull(), any(CareerAgentService.StreamSink.class));
        var worker = new AgentMessageQueueWorker(queue, service, runs,
                new AgentMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        try {
            worker.poll();
            if (thrown) verify(queue, timeout(2000)).interrupted(item);
            else if (requeue) verify(queue, timeout(2000)).notStarted(item);
            else verify(queue, timeout(2000)).failedBeforeStart(item);
            verify(service, times(1)).chatStream(eq(owner), eq(input.conversationId()), eq(input.message()), isNull(), isNull(),
                    eq(List.of()), eq(id), eq(ReasoningDepth.DEEP), isNull(), any(CareerAgentService.StreamSink.class));
            if (!requeue) verify(queue, never()).notStarted(item);
        } finally { worker.stop(); }
    }

    @Test void configuredCapacityStopsTheThirdConcurrentClaim() throws Exception {
        var queue = mock(AgentMessageQueue.class);
        var service = mock(CareerAgentService.class);
        var runs = mock(AgentRunStore.class);
        UUID owner = UUID.randomUUID();
        var first = item(owner);
        var second = item(owner);
        var claims = new AtomicInteger();
        when(queue.claimNext()).thenAnswer(call -> switch (claims.incrementAndGet()) {
            case 1 -> Optional.of(first);
            case 2 -> Optional.of(second);
            default -> Optional.empty();
        });
        var started = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            CareerAgentService.StreamSink sink = call.getArgument(9);
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            sink.complete();
            return null;
        }).when(service).chatStream(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(CareerAgentService.StreamSink.class));
        when(runs.get(any(), any())).thenThrow(new ResourceNotFoundException("请求记录不存在"));
        var worker = new AgentMessageQueueWorker(queue, service, runs,
                new AgentMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()), 2);

        worker.poll();
        worker.poll();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        worker.poll();
        assertThat(claims.get()).isEqualTo(2);
        release.countDown();
        verify(queue, timeout(2000).times(2)).failedBeforeStart(any());
        worker.stop();
    }

    private static AgentMessageQueue.Item item(UUID owner) {
        UUID id = UUID.randomUUID();
        var input = new AgentMessageRequest(UUID.randomUUID(), "Synthetic input", null, null, List.of(), id,
                ReasoningDepth.DEEP);
        return new AgentMessageQueue.Item(id, owner, "DISPATCHING", input, null, null, Instant.now());
    }
}
