package com.projectardor.platform.jobs.aijobs;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;
import com.projectardor.platform.model.llm.service.LlmCallException;

@Component
public class AiJobWorker {
    private final AiJobStore store;
    private final AiJobService service;
    private final Semaphore slots = new Semaphore(2);
    private final ConcurrentHashMap<UUID, AiJobStore.Job> active = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(2, Thread.ofPlatform().name("ai-evaluation-", 0).factory());
    public AiJobWorker(AiJobStore store, AiJobService service) { this.store = store; this.service = service; }

    @Scheduled(fixedDelay = 1000)
    public void poll() {
        if (!slots.tryAcquire()) return;
        try {
            var next = store.claimNext();
            if (next.isEmpty()) { slots.release(); return; }
            var job = next.get();
            active.put(job.id(), job);
            try { executor.execute(() -> {
                try { store.complete(job, service.perform(job)); }
                catch (RuntimeException failure) {
                    String code = failure instanceof LlmCallException llm ? llm.getCode() : "AI_EVALUATION_FAILED";
                    String message = failure instanceof LlmCallException || failure instanceof IllegalStateException || failure instanceof IllegalArgumentException
                            ? failure.getMessage() : "评估未完成，原提交已保留，请重试";
                    store.fail(job, code, message == null ? "评估未完成，请重试" : message);
                } finally { active.remove(job.id()); slots.release(); }
            }); } catch (RuntimeException rejected) {
                active.remove(job.id());
                store.fail(job, "AI_JOB_INTERRUPTED", "评估未启动，原提交已保留，可重试");
                throw rejected;
            }
        } catch (RuntimeException failure) { slots.release(); throw failure; }
    }

    @Scheduled(fixedDelayString = "${app.ai-jobs.maintenance-delay:30s}")
    public void maintain() {
        active.values().forEach(store::heartbeat);
        store.recoverStale();
    }
    @PreDestroy public void stop() { executor.shutdown(); }
}
