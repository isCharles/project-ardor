package com.projectardor.jobsearch.discovery.service;

import org.springframework.stereotype.Component;

import com.projectardor.platform.jobs.aijobs.AiJobHandler;
import com.projectardor.platform.jobs.aijobs.AiJobType;
import com.projectardor.platform.jobs.aijobs.AiJobWork;

/** Executes one persisted discovery run on the existing AI job queue. */
@Component
public class JobDiscoveryJobHandler implements AiJobHandler {

    private final JobDiscoveryService service;

    public JobDiscoveryJobHandler(JobDiscoveryService service) {
        this.service = service;
    }

    @Override
    public AiJobType type() {
        return AiJobType.JOB_DISCOVERY;
    }

    @Override
    public String perform(AiJobWork work) {
        return service.perform(work.userId(), work.resourceId());
    }
}
