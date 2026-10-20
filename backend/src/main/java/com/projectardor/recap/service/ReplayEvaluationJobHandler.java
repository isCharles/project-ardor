package com.projectardor.recap.service;

import java.util.UUID;
import org.springframework.stereotype.Component;
import com.projectardor.aijobs.AiJobHandler;
import com.projectardor.aijobs.AiJobType;
import com.projectardor.aijobs.AiJobWork;
import tools.jackson.databind.ObjectMapper;

@Component
public class ReplayEvaluationJobHandler implements AiJobHandler {
    private final InterviewReplayService replay;
    private final ObjectMapper mapper;
    public ReplayEvaluationJobHandler(InterviewReplayService replay,ObjectMapper mapper) { this.replay=replay;this.mapper=mapper; }
    public AiJobType type() { return AiJobType.REPLAY_EVALUATE; }
    public void validateEnqueue(UUID owner,UUID resource,String payload,boolean existingRequest) { replay.view(owner,resource); }
    public String perform(AiJobWork work) {
        var input=mapper.readValue(work.payload(),ReplayEvaluationCommand.class);
        replay.submit(work.userId(),work.resourceId(),work.id(),input.answer(),input.retestTaskId());
        return "/app/replay?question="+work.resourceId()+(input.retestTaskId() == null ? "" : "&retest="+input.retestTaskId());
    }
}
