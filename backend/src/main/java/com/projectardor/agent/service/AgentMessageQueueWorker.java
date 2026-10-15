package com.projectardor.agent.service;

import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.projectardor.agent.web.AgentStreamEvent;
import com.projectardor.common.web.ResourceNotFoundException;
import jakarta.annotation.PreDestroy;

@Component
public class AgentMessageQueueWorker {
    private final AgentMessageQueue queue;
    private final CareerAgentService agent;
    private final AgentRunStore runs;
    private final Semaphore slots = new Semaphore(2);
    private final ExecutorService executor = Executors.newFixedThreadPool(2, Thread.ofPlatform().name("agent-queue-", 0).factory());
    public AgentMessageQueueWorker(AgentMessageQueue queue, CareerAgentService agent, AgentRunStore runs) {
        this.queue = queue; this.agent = agent; this.runs = runs;
    }
    @Scheduled(fixedDelay = 1000)
    public void poll() {
        queue.reconcile();
        if (!slots.tryAcquire()) return;
        try {
            var next = queue.claimNext();
            if (next.isEmpty()) { slots.release(); return; }
            var item = next.get();
            try { executor.execute(() -> dispatch(item)); }
            catch (RuntimeException rejected) { queue.notStarted(item); slots.release(); }
        } catch (RuntimeException failure) { slots.release(); throw failure; }
    }
    private void dispatch(AgentMessageQueue.Item item) {
        AtomicBoolean released = new AtomicBoolean();
        AtomicReference<String> errorCode = new AtomicReference<>();
        var request = item.request();
        try {
            agent.chatStream(item.userId(), request.conversationId(), request.message(), request.contextType(),
                    request.contextId(), request.contextReferences(), request.requestId(), request.reasoningDepth(),
                    new CareerAgentService.StreamSink() {
                        @Override public void send(AgentStreamEvent event) {
                            if ("error".equals(event.type())) errorCode.set(event.errorCode());
                        }
                        @Override public void complete() {
                            if (!released.compareAndSet(false, true)) return;
                            try {
                                try { runs.get(item.userId(), item.id()); queue.reconcile(); }
                                catch (ResourceNotFoundException neverStarted) {
                                    if ("AGENT_BUSY".equals(errorCode.get())) queue.notStarted(item);
                                    else queue.failedBeforeStart(item);
                                }
                            } finally { slots.release(); }
                        }
                    });
        } catch (RuntimeException failure) {
            if (released.compareAndSet(false, true)) {
                try { queue.interrupted(item); } finally { slots.release(); }
            }
        }
    }
    @PreDestroy public void stop() { executor.shutdown(); }
}
