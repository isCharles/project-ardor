package com.projectardor.learning.service;

import org.springframework.stereotype.Component;
import com.projectardor.aijobs.AiJobHandler;
import com.projectardor.aijobs.AiJobType;
import com.projectardor.aijobs.AiJobWork;
import tools.jackson.databind.ObjectMapper;

@Component
public class LearningCreationJobHandler implements AiJobHandler {
    private final LearningPlanService learning;
    private final ObjectMapper mapper;
    public LearningCreationJobHandler(LearningPlanService learning,ObjectMapper mapper) { this.learning=learning;this.mapper=mapper; }
    public AiJobType type() { return AiJobType.LEARNING_CREATE; }
    public String perform(AiJobWork work) {
        var input=mapper.readValue(work.payload(),LearningCreationCommand.class);
        var plan=learning.create(work.userId(),work.id(),input.concept(),input.reason(),input.sourceType(),input.sourceId(),input.scheduledAt(),input.context());
        return "/app/learning?id="+plan.getId();
    }
}
