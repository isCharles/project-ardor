package com.projectardor.resume.service;

import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.projectardor.resume.domain.ResumeAnalysisJob;

@Component
public class ResumeAnalysisWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(ResumeAnalysisWorker.class);

    private final ResumeAnalysisQueueService queueService;
    private final ResumeService resumeService;
    private final AtomicInteger recoveryTicks = new AtomicInteger();

    public ResumeAnalysisWorker(ResumeAnalysisQueueService queueService, ResumeService resumeService) {
        this.queueService = queueService;
        this.resumeService = resumeService;
    }

    @Scheduled(fixedDelayString = "${app.jobs.resume-analysis-poll-delay:1000}")
    public void poll() {
        if (recoveryTicks.getAndIncrement() % 60 == 0) {
            int recovered = queueService.recoverStaleJobs();
            if (recovered > 0) LOGGER.warn("Recovered {} stale resume analysis job(s)", recovered);
        }
        queueService.claimNext().ifPresent(this::process);
    }

    private void process(ResumeAnalysisJob job) {
        try {
            var analysis = resumeService.analyze(job.getUserId(), job.getResumeId());
            queueService.complete(job.getId(), analysis.getId());
        } catch (RuntimeException failure) {
            LOGGER.warn("Resume analysis job {} failed: {}", job.getId(), failure.getClass().getSimpleName());
            queueService.fail(job.getId(), failure);
        }
    }
}
