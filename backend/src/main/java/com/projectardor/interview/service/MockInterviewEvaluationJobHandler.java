package com.projectardor.interview.service;

import java.util.UUID;
import org.springframework.stereotype.Component;
import com.projectardor.platform.jobs.aijobs.AiJobHandler;
import com.projectardor.platform.jobs.aijobs.AiJobType;
import com.projectardor.platform.jobs.aijobs.AiJobWork;

/** Legacy task name remains INTERVIEW_EVALUATE; it still means mock interview, never a real round. */
@Component
public class MockInterviewEvaluationJobHandler implements AiJobHandler {
    private final InterviewService interviews;
    public MockInterviewEvaluationJobHandler(InterviewService interviews) { this.interviews=interviews; }
    public AiJobType type() { return AiJobType.INTERVIEW_EVALUATE; }
    public void validateEnqueue(UUID owner,UUID resource,String payload,boolean existingRequest) { interviews.get(owner,resource); }
    public String perform(AiJobWork work) {
        interviews.finish(work.userId(),work.resourceId());
        return "/app/interviews?session="+work.resourceId();
    }
}
