package com.projectardor.learning.practice.mockinterview.service;

import java.util.UUID;
import org.springframework.stereotype.Component;
import com.projectardor.learning.practice.mockinterview.domain.InterviewMode;
import com.projectardor.learning.practice.mockinterview.service.adaptive.AdaptiveInterviewOrchestrator;
import com.projectardor.platform.jobs.aijobs.AiJobHandler;
import com.projectardor.platform.jobs.aijobs.AiJobType;
import com.projectardor.platform.jobs.aijobs.AiJobWork;

/** Legacy task name remains INTERVIEW_EVALUATE; it still means mock interview, never a real round. */
@Component
public class MockInterviewEvaluationJobHandler implements AiJobHandler {
    private final InterviewService interviews;
    private final AdaptiveInterviewOrchestrator adaptiveInterviews;
    public MockInterviewEvaluationJobHandler(InterviewService interviews,
            AdaptiveInterviewOrchestrator adaptiveInterviews) {
        this.interviews=interviews;
        this.adaptiveInterviews=adaptiveInterviews;
    }
    public AiJobType type() { return AiJobType.INTERVIEW_EVALUATE; }
    public void validateEnqueue(UUID owner,UUID resource,String payload,boolean existingRequest) { interviews.get(owner,resource); }
    public String perform(AiJobWork work) {
        if (interviews.get(work.userId(),work.resourceId()).getMode()==InterviewMode.ADAPTIVE) {
            adaptiveInterviews.finish(work.userId(),work.resourceId());
        } else {
            interviews.finish(work.userId(),work.resourceId());
        }
        return "/app/interviews?session="+work.resourceId();
    }
}