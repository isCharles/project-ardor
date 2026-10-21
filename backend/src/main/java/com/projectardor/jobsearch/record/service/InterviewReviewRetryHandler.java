package com.projectardor.jobsearch.record.service;

import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import com.projectardor.platform.notification.BackgroundRetryHandler;
import com.projectardor.platform.notification.BackgroundTaskStore;

@Component
public class InterviewReviewRetryHandler implements BackgroundRetryHandler {
    private final InterviewMinutesService minutes;
    public InterviewReviewRetryHandler(InterviewMinutesService minutes) { this.minutes=minutes; }
    public Set<String> kinds() { return Set.of("RECAP_REVIEW"); }
    public void retry(UUID owner, BackgroundTaskStore.Task task) { minutes.retryReview(owner,task.id()); }
}
