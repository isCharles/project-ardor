package com.projectardor.platform.jobs.aijobs;

import java.util.UUID;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import com.projectardor.learning.plan.service.LearningCreationCommand;
import com.projectardor.learning.practice.exercises.service.LearningEvaluationCommand;
import com.projectardor.learning.practice.question.service.ReplayEvaluationCommand;
import com.projectardor.platform.usage.MembershipService;
import tools.jackson.databind.ObjectMapper;

@Service
public class AiJobService {
    private final AiJobStore store;
    private final Map<AiJobType,AiJobHandler> handlers;
    private final ObjectMapper mapper;
    private final MembershipService memberships;
    public AiJobService(AiJobStore store,List<AiJobHandler> registered,ObjectMapper mapper,MembershipService memberships) {
        this.store=store; this.mapper=mapper; this.memberships=memberships;
        var map=new EnumMap<AiJobType,AiJobHandler>(AiJobType.class);
        for(var handler:registered) {
            if (handler.type() == null || map.putIfAbsent(handler.type(),handler) != null)
                throw new IllegalStateException("Duplicate or invalid AI job handler");
        }
        if (map.size() != AiJobType.values().length) throw new IllegalStateException("Missing AI job handler");
        handlers=Map.copyOf(map);
    }

    public AiJobResponse createLearning(UUID userId, LearningCreationCommand input) {
        return enqueue(userId,input.requestId(),AiJobType.LEARNING_CREATE,null,input);
    }

    public AiJobResponse status(UUID userId, UUID id) {
        AiJobStore.Job job = store.get(userId, id);
        int pos = "QUEUED".equals(job.status()) ? store.queuePosition(job) : -1;
        return AiJobResponse.from(job, pos);
    }

    public AiJobResponse evaluateLearning(UUID userId, UUID planId, LearningEvaluationCommand input) {
        return enqueue(userId,input.requestId(),AiJobType.LEARNING_EVALUATE,planId,input);
    }

    public AiJobResponse evaluateReplay(UUID userId, UUID questionId, ReplayEvaluationCommand input) {
        return enqueue(userId,input.requestId(),AiJobType.REPLAY_EVALUATE,questionId,input);
    }

    public AiJobResponse evaluateInterview(UUID userId, UUID sessionId, UUID requestId) {
        return enqueue(userId,requestId,AiJobType.INTERVIEW_EVALUATE,sessionId,Map.of());
    }

    public AiJobResponse enqueue(UUID owner,UUID request,AiJobType type,UUID resource,Object command) {
        String payload=mapper.writeValueAsString(command);
        boolean existing=request != null && store.find(owner,request).isPresent();
        handler(type).validateEnqueue(owner,resource,payload,existing);
        int priority = priority(owner);
        return AiJobResponse.from(store.enqueue(owner,request,type,resource,payload,priority), -1);
    }
    public String perform(AiJobStore.Job job) {
        return handler(job.type()).perform(new AiJobWork(job.id(),job.userId(),job.type(),job.resourceId(),job.payload()));
    }
    private AiJobHandler handler(AiJobType type) {
        var handler=handlers.get(type);
        if (handler == null) throw new IllegalArgumentException("Unsupported AI job type");
        return handler;
    }

    int priority(UUID userId) {
        var tier = memberships.tier(userId);
        return tier.paid() ? 1 : 0;
    }
}
