package com.projectardor.assistant.execution.service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.projectardor.assistant.execution.web.AgentStreamEvent;
import com.projectardor.common.web.ResourceNotFoundException;
import jakarta.annotation.PreDestroy;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "app.agent.queue.enabled", matchIfMissing = true)
public class AgentMessageQueueWorker {
    private final AgentMessageQueue queue;
    private final CareerAgentService agent;
    private final AgentRunStore runs;
    private final AgentMetrics metrics;
    private final Semaphore slots;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    public AgentMessageQueueWorker(AgentMessageQueue queue, CareerAgentService agent, AgentRunStore runs,
            AgentMetrics metrics) {
        this(queue, agent, runs, metrics, 2);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public AgentMessageQueueWorker(AgentMessageQueue queue, CareerAgentService agent, AgentRunStore runs,
            AgentMetrics metrics,
            @org.springframework.beans.factory.annotation.Value("${app.agent.queue.capacity:2}") int capacity) {
        this.queue = queue; this.agent = agent; this.runs = runs; this.metrics = metrics;
        this.slots = new Semaphore(Math.max(1, capacity));
    }
    @Scheduled(fixedDelay = 1000)
    public void poll() {
        queue.reconcile();
        if (!slots.tryAcquire()) return;
        try {
            var next = queue.claimNext();
            if (next.isEmpty()) { slots.release(); return; }
            var item = next.get();
            metrics.recordQueueClaim(Duration.between(item.createdAt(), Instant.now()).toNanos());
            try { executor.execute(() -> dispatch(item)); }
            catch (RuntimeException rejected) {
                queue.notStarted(item);
                metrics.recordQueueFailure("executor_rejected");
                slots.release();
            }
        } catch (RuntimeException failure) { slots.release(); throw failure; }
    }
    private void dispatch(AgentMessageQueue.Item item) {
        AgentQueueSlot slot = new AgentQueueSlot(slots);
        AtomicBoolean released = new AtomicBoolean();
        AtomicReference<String> errorCode = new AtomicReference<>();
        var request = item.request();
        try {
            agent.chatStream(item.userId(), request.conversationId(), request.message(), request.contextType(),
                    request.contextId(), request.contextReferences(), request.requestId(), request.reasoningDepth(),
                    request.modelChoice(), new CareerAgentService.StreamSink() {
                        @Override public void waitingForApproval(boolean waiting) {
                            if (waiting) slot.pause(); else slot.resume();
                        }
                        @Override public void send(AgentStreamEvent event) {
                            if ("error".equals(event.type())) errorCode.set(event.errorCode());
                        }
                        @Override public void complete() {
                            if (!released.compareAndSet(false, true)) return;
                            try {
                                try { runs.get(item.userId(), item.id()); queue.reconcile(); }
                                catch (ResourceNotFoundException neverStarted) {
                                    if ("AGENT_BUSY".equals(errorCode.get())) queue.notStarted(item);
                                    else {
                                        queue.failedBeforeStart(item);
                                        metrics.recordQueueFailure("failed_before_start");
                                    }
                                }
                            } finally { slot.close(); }
                        }
                    });
        } catch (RuntimeException failure) {
            if (released.compareAndSet(false, true)) {
                try {
                    queue.interrupted(item);
                    metrics.recordQueueFailure("interrupted");
                } finally { slot.close(); }
            }
        }
    }
    @PreDestroy public void stop() { executor.shutdown(); }
}
