package com.projectardor.aijobs;

import java.util.UUID;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import com.projectardor.learning.service.LearningCreationCommand;
import com.projectardor.learning.service.LearningEvaluationCommand;
import com.projectardor.recap.service.ReplayEvaluationCommand;
import tools.jackson.databind.ObjectMapper;

@Service
public class AiJobService {
    private final AiJobStore store;
    private final Map<AiJobType,AiJobHandler> handlers;
    private final ObjectMapper mapper;
    public AiJobService(AiJobStore store,List<AiJobHandler> registered,ObjectMapper mapper) {
        this.store=store; this.mapper=mapper;
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

    public AiJobResponse status(UUID userId, UUID id) { return AiJobResponse.from(store.get(userId, id)); }

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
        return AiJobResponse.from(store.enqueue(owner,request,type,resource,payload));
    }
    public String perform(AiJobStore.Job job) {
        return handler(job.type()).perform(new AiJobWork(job.id(),job.userId(),job.type(),job.resourceId(),job.payload()));
    }
    private AiJobHandler handler(AiJobType type) {
        var handler=handlers.get(type);
        if (handler == null) throw new IllegalArgumentException("Unsupported AI job type");
        return handler;
    }
}
