package com.projectardor.jobsearch.discovery.service;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.projectardor.platform.jobs.aijobs.AiJobStore;
import com.projectardor.platform.jobs.aijobs.AiJobType;
import com.projectardor.platform.notification.BackgroundRetryHandler;
import com.projectardor.platform.notification.BackgroundTaskStore;

/**
 * A terminal discovery run gets a fresh run with the same criteria, while an
 * unfinished run is simply re-enqueued so the worker resumes it.
 */
@Component
public class JobDiscoveryRetryHandler implements BackgroundRetryHandler {

    private final JobDiscoveryService service;
    private final AiJobStore jobs;

    public JobDiscoveryRetryHandler(JobDiscoveryService service, AiJobStore jobs) {
        this.service = service;
        this.jobs = jobs;
    }

    @Override
    public Set<String> kinds() {
        return Set.of(AiJobType.JOB_DISCOVERY.name());
    }

    @Override
    public void retry(UUID owner, BackgroundTaskStore.Task task) {
        var run = service.run(owner, task.resourceId());
        if (List.of("FAILED", "PARTIAL", "CANCELED").contains(run.status())) {
            service.retry(owner, task.resourceId());
        } else {
            jobs.retry(owner, task.id());
        }
    }
}
