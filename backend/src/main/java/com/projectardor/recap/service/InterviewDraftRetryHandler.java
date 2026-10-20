package com.projectardor.recap.service;

import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import com.projectardor.background.BackgroundRetryHandler;
import com.projectardor.background.BackgroundTaskStore;

@Component
public class InterviewDraftRetryHandler implements BackgroundRetryHandler {
    private final InterviewRecapQueueService recaps;
    public InterviewDraftRetryHandler(InterviewRecapQueueService recaps) { this.recaps=recaps; }
    public Set<String> kinds() { return Set.of("RECAP_ORGANIZE"); }
    public void retry(UUID owner, BackgroundTaskStore.Task task) { recaps.retryFailed(owner,task.id()); }
}
