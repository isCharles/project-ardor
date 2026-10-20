package com.projectardor.learning.service;

import java.util.UUID;
import org.springframework.stereotype.Component;
import com.projectardor.aijobs.AiJobHandler;
import com.projectardor.aijobs.AiJobType;
import com.projectardor.aijobs.AiJobWork;
import tools.jackson.databind.ObjectMapper;

@Component
public class LearningEvaluationJobHandler implements AiJobHandler {
    private final LearningPlanService learning;
    private final ObjectMapper mapper;
    public LearningEvaluationJobHandler(LearningPlanService learning,ObjectMapper mapper) { this.learning=learning;this.mapper=mapper; }
    public AiJobType type() { return AiJobType.LEARNING_EVALUATE; }
    public void validateEnqueue(UUID owner,UUID resource,String payload,boolean existingRequest) {
        var plan=learning.get(owner,resource);
        if (existingRequest) return; // Preserve repeat submissions after the original attempt advanced the plan.
        var input=mapper.readValue(payload,LearningEvaluationCommand.class);
        if (input.expectedAttemptCount() == null || input.expectedAttemptCount() != plan.getAttemptCount())
            throw new IllegalStateException("学习轮次已更新，请刷新后重新提交");
        if (input.answers() == null || input.answers().size() != plan.getExercises().size())
            throw new IllegalArgumentException("请完成全部练习后再提交");
    }
    public String perform(AiJobWork work) {
        var input=mapper.readValue(work.payload(),LearningEvaluationCommand.class);
        var plan=learning.submit(work.userId(),work.resourceId(),input.answers(),input.expectedAttemptCount(),work.id());
        return "/app/learning?id="+plan.getId();
    }
}
