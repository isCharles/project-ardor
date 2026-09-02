package com.projectardor.recap.service;

import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.projectardor.recap.domain.InterviewRecapJob;

@Component
public class InterviewRecapWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(InterviewRecapWorker.class);
    private final InterviewRecapQueueService queueService;
    private final InterviewRecapService recapService;
    private final AtomicInteger recoveryTicks = new AtomicInteger();

    public InterviewRecapWorker(InterviewRecapQueueService queueService, InterviewRecapService recapService) {
        this.queueService = queueService; this.recapService = recapService;
    }

    @Scheduled(fixedDelayString = "${app.jobs.interview-recap-poll-delay:1000}")
    public void poll() {
        if (recoveryTicks.getAndIncrement() % 60 == 0) {
            int recovered = queueService.recoverStaleJobs();
            if (recovered > 0) LOGGER.warn("Recovered {} stale interview recap job(s)", recovered);
        }
        queueService.claimNext().ifPresent(this::process);
    }

    private void process(InterviewRecapJob job) {
        try {
            var recap = recapService.organize(job.getUserId(), job.getRawContent());
            queueService.complete(job.getId(), recap.id());
        } catch (RuntimeException failure) {
            LOGGER.warn("Interview recap job {} failed: {}", job.getId(), failure.getClass().getSimpleName());
            queueService.fail(job.getId(), failure);
        }
    }
}
