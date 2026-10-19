package com.projectardor.background;

import java.util.UUID;
import org.springframework.stereotype.Service;
import com.projectardor.aijobs.AiJobStore;
import com.projectardor.resume.service.ResumeAnalysisQueueService;
import com.projectardor.recap.service.InterviewRecapQueueService;
import com.projectardor.knowledge.service.KnowledgeIndexTaskStore;
import com.projectardor.knowledge.service.KnowledgeIndexDispatcher;

@Service
public class BackgroundTaskService {
    private final BackgroundTaskStore store;
    private final AiJobStore aiJobs;
    private final ResumeAnalysisQueueService resumes;
    private final InterviewRecapQueueService recaps;
    private final KnowledgeIndexTaskStore knowledge;
    private final KnowledgeIndexDispatcher indexDispatcher;
    private final com.projectardor.recap.service.InterviewMinutesService minutes;
    public BackgroundTaskService(BackgroundTaskStore store, AiJobStore aiJobs, ResumeAnalysisQueueService resumes,
            InterviewRecapQueueService recaps, KnowledgeIndexTaskStore knowledge,KnowledgeIndexDispatcher indexDispatcher,
            com.projectardor.recap.service.InterviewMinutesService minutes) {
        this.store=store; this.aiJobs=aiJobs; this.resumes=resumes; this.recaps=recaps; this.knowledge=knowledge;
        this.indexDispatcher=indexDispatcher;
        this.minutes=minutes;
    }
    public void retry(UUID userId, String kind, UUID id, String token) {
        var task = store.get(userId,kind,id);
        if (!"FAILED".equals(task.status())) throw new IllegalStateException("只有失败任务可以重试");
        store.read(userId,kind,id,token);
        switch (kind) {
            case "RESUME_ANALYSIS" -> resumes.request(userId,task.resourceId());
            case "RECAP_ORGANIZE" -> recaps.retryFailed(userId,id);
            case "RECAP_REVIEW" -> minutes.retryReview(userId,id);
            case "KNOWLEDGE_INDEX" -> { knowledge.retry(userId,id); indexDispatcher.dispatchPending(userId); }
            default -> aiJobs.retry(userId,id);
        }
    }
}
