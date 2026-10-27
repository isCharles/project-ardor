package com.projectardor.platform.jobs.aijobs;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;
import com.projectardor.assistant.execution.service.AgentMetrics;
import com.projectardor.platform.model.llm.service.LlmCallException;

@Component
public class AiJobWorker {
    private final AiJobStore store;
    private final AiJobService service;
    private final AgentMetrics metrics;
    private final Semaphore slots;
    private final ConcurrentHashMap<UUID, AiJobStore.Job> active = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    public AiJobWorker(AiJobStore store, AiJobService service, AgentMetrics metrics) {
        this(store, service, metrics, 2);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public AiJobWorker(AiJobStore store, AiJobService service, AgentMetrics metrics,
            @org.springframework.beans.factory.annotation.Value("${app.ai-jobs.worker-capacity:2}") int capacity) {
        this.store = store; this.service = service; this.metrics = metrics;
        int slotsCount = Math.max(1, capacity);
        this.slots = new Semaphore(slotsCount);
        this.executor = Executors.newFixedThreadPool(slotsCount,
                Thread.ofPlatform().name("ai-evaluation-", 0).factory());
    }

    @Scheduled(fixedDelay = 1000)
    public void poll() {
        if (!slots.tryAcquire()) return;
        try {
            var next = store.claimNext();
            if (next.isEmpty()) { slots.release(); return; }
            var job = next.get();
            active.put(job.id(), job);
            try { executor.execute(() -> {
                try {
                    if (store.complete(job, service.perform(job)))
                        metrics.recordJobCompletion(job.type().name(), "COMPLETED");
                } catch (RuntimeException failure) { handleFailure(job, failure); }
                finally { active.remove(job.id(), job); slots.release(); }
            }); } catch (RuntimeException rejected) {
                active.remove(job.id(), job);
                if (store.requeue(job, Instant.now().plusSeconds(30)))
                    metrics.recordJobRetry(job.type().name());
                throw rejected;
            }
        } catch (RuntimeException failure) { slots.release(); throw failure; }
    }

    void handleFailure(AiJobStore.Job job, RuntimeException failure) {
        boolean retryable = isRetryable(failure);
        int attempt = job.attempts();
        int max = job.maxAttempts();
        boolean applied;
        if (retryable && attempt < max) {
            long backoffSeconds = Math.min(30L * (1L << (attempt - 1)), 600L);
            applied = store.requeue(job, Instant.now().plusSeconds(backoffSeconds));
            if (applied) metrics.recordJobRetry(job.type().name());
        } else if (retryable) {
            applied = store.deadLetter(job, "AI_JOB_EXHAUSTED",
                    "评估在 " + attempt + " 次尝试后仍未完成，已转入死信，可手动重试");
            if (applied) metrics.recordJobDeadLetter(job.type().name());
        } else {
            String code = failure instanceof LlmCallException llm ? llm.getCode() : "AI_EVALUATION_FAILED";
            String message = failure instanceof LlmCallException || failure instanceof IllegalStateException
                    || failure instanceof IllegalArgumentException
                    ? failure.getMessage() : "评估未完成，原提交已保留，请重试";
            applied = store.fail(job, code, message == null ? "评估未完成，请重试" : message);
            if (applied) metrics.recordJobCompletion(job.type().name(), "FAILED");
        }
    }

    private boolean isRetryable(RuntimeException failure) {
        if (failure instanceof LlmCallException llm) return llm.isRetryable();
        Throwable cause = failure.getCause();
        return failure instanceof java.util.concurrent.RejectedExecutionException
                || cause instanceof java.net.http.HttpTimeoutException
                || cause instanceof java.net.ConnectException;
    }

    @Scheduled(fixedDelayString = "${app.ai-jobs.maintenance-delay:30s}")
    public void maintain() {
        active.values().forEach(store::heartbeat);
        store.recoverStale();
    }
    @PreDestroy public void stop() { executor.shutdown(); }
}
