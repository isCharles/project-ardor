package com.projectardor.materials.resume.service;

import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import com.projectardor.platform.notification.BackgroundRetryHandler;
import com.projectardor.platform.notification.BackgroundTaskStore;

@Component
public class ResumeAnalysisRetryHandler implements BackgroundRetryHandler {
    private final ResumeAnalysisQueueService resumes;
    public ResumeAnalysisRetryHandler(ResumeAnalysisQueueService resumes) { this.resumes=resumes; }
    public Set<String> kinds() { return Set.of("RESUME_ANALYSIS"); }
    public void retry(UUID owner, BackgroundTaskStore.Task task) { resumes.request(owner,task.resourceId()); }
}
