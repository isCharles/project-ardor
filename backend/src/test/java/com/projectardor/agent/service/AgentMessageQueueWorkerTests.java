package com.projectardor.agent.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.projectardor.agent.ReasoningDepth;
import com.projectardor.agent.web.AgentMessageRequest;
import com.projectardor.agent.web.AgentStreamEvent;
import com.projectardor.common.web.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
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
            CareerAgentService.StreamSink sink = call.getArgument(8);
            sink.send(AgentStreamEvent.error(code, "Synthetic failure", false, 0));
            sink.complete();
            sink.complete(); // Duplicate transport callbacks cannot release slots or dispatch again.
            return null;
        }).when(service).chatStream(eq(owner), eq(input.conversationId()), eq(input.message()), isNull(), isNull(),
                eq(List.of()), eq(id), eq(ReasoningDepth.DEEP), any(CareerAgentService.StreamSink.class));
        var worker = new AgentMessageQueueWorker(queue, service, runs);
        try {
            worker.poll();
            if (thrown) verify(queue, timeout(2000)).interrupted(item);
            else if (requeue) verify(queue, timeout(2000)).notStarted(item);
            else verify(queue, timeout(2000)).failedBeforeStart(item);
            verify(service, times(1)).chatStream(eq(owner), eq(input.conversationId()), eq(input.message()), isNull(), isNull(),
                    eq(List.of()), eq(id), eq(ReasoningDepth.DEEP), any(CareerAgentService.StreamSink.class));
            if (!requeue) verify(queue, never()).notStarted(item);
        } finally { worker.stop(); }
    }
}
