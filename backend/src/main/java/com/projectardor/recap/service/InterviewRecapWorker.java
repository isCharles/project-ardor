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
    private final InterviewMinutesService minutes;
    private final AtomicInteger recoveryTicks = new AtomicInteger();

    public InterviewRecapWorker(InterviewRecapQueueService queueService, InterviewRecapService recapService,
            InterviewMinutesService minutes) {
        this.queueService = queueService; this.recapService = recapService; this.minutes = minutes;
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
            minutes.prepare(job);
            queueService.complete(job.getId(), null);
        } catch (RuntimeException failure) {
            LOGGER.warn("Interview recap job {} failed: {}", job.getId(), failure.getClass().getSimpleName());
            queueService.fail(job.getId(), failure);
        }
    }

    @Scheduled(fixedDelayString = "${app.jobs.interview-review-poll-delay:1000}")
    public void reviewConfirmedMinutes() {
        var work = minutes.claim();
        if (work == null) return;
        try {
            var recap = recapService.organizeConfirmed(work.userId(), work.minutes(), minutes.opportunity(work), work.id());
            minutes.finish(work, recap.id(), false);
        } catch (RuntimeException failure) {
            LOGGER.warn("Confirmed interview review {} failed: {}", work.id(),failure.getClass().getSimpleName());
            minutes.finish(work,null,true);
        }
    }
}
