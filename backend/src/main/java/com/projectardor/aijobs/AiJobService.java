package com.projectardor.aijobs;

import java.util.UUID;
import org.springframework.stereotype.Service;
import com.projectardor.learning.service.LearningPlanService;
import com.projectardor.learning.web.LearningPlanCreateRequest;
import com.projectardor.learning.web.LearningEvaluationQueueRequest;
import com.projectardor.recap.service.InterviewReplayService;
import com.projectardor.recap.web.InterviewReplaySubmitRequest;
import com.projectardor.interview.service.InterviewService;
import tools.jackson.databind.ObjectMapper;

@Service
public class AiJobService {
    private final AiJobStore store;
    private final LearningPlanService learning;
    private final InterviewReplayService replay;
    private final InterviewService interviews;
    private final ObjectMapper mapper;
    public AiJobService(AiJobStore store, LearningPlanService learning, InterviewReplayService replay,
            InterviewService interviews, ObjectMapper mapper) {
        this.store = store; this.learning = learning; this.replay = replay; this.interviews = interviews; this.mapper = mapper;
    }

    public AiJobResponse createLearning(UUID userId, LearningPlanCreateRequest input) {
        return AiJobResponse.from(store.enqueue(userId, input.requestId(), AiJobType.LEARNING_CREATE, null, json(input)));
    }

    public AiJobResponse status(UUID userId, UUID id) { return AiJobResponse.from(store.get(userId, id)); }

    public AiJobResponse evaluateLearning(UUID userId, UUID planId, LearningEvaluationQueueRequest input) {
        var plan = learning.get(userId, planId);
        if (store.find(userId, input.requestId()).isPresent())
            return AiJobResponse.from(store.enqueue(userId, input.requestId(), AiJobType.LEARNING_EVALUATE, planId, json(input)));
        if (input.expectedAttemptCount() != plan.getAttemptCount()) throw new IllegalStateException("学习轮次已更新，请刷新后重新提交");
        if (input.answers().size() != plan.getExercises().size()) throw new IllegalArgumentException("请完成全部练习后再提交");
        return AiJobResponse.from(store.enqueue(userId, input.requestId(), AiJobType.LEARNING_EVALUATE, planId, json(input)));
    }

    public AiJobResponse evaluateReplay(UUID userId, UUID questionId, InterviewReplaySubmitRequest input) {
        replay.view(userId, questionId);
        return AiJobResponse.from(store.enqueue(userId, input.requestId(), AiJobType.REPLAY_EVALUATE, questionId, json(input)));
    }

    public AiJobResponse evaluateInterview(UUID userId, UUID sessionId, UUID requestId) {
        interviews.get(userId, sessionId);
        return AiJobResponse.from(store.enqueue(userId, requestId, AiJobType.INTERVIEW_EVALUATE, sessionId, "{}"));
    }

    public String perform(AiJobStore.Job job) {
        return switch (job.type()) {
            case LEARNING_CREATE -> {
                var input = mapper.readValue(job.payload(), LearningPlanCreateRequest.class);
                var plan = learning.create(job.userId(), job.id(), input.concept(), input.reason(), input.sourceType(), input.sourceId(), input.scheduledAt(), input.context());
                yield "/app/learning?id=" + plan.getId();
            }
            case LEARNING_EVALUATE -> {
                var input = mapper.readValue(job.payload(), LearningEvaluationQueueRequest.class);
                var plan = learning.submit(job.userId(), job.resourceId(), input.answers(), input.expectedAttemptCount(), job.id());
                yield "/app/learning?id=" + plan.getId();
            }
            case REPLAY_EVALUATE -> {
                var input = mapper.readValue(job.payload(), InterviewReplaySubmitRequest.class);
                replay.submit(job.userId(), job.resourceId(), job.id(), input.answer(), input.retestTaskId());
                yield "/app/replay?question=" + job.resourceId() + (input.retestTaskId() == null ? "" : "&retest=" + input.retestTaskId());
            }
            case INTERVIEW_EVALUATE -> {
                interviews.finish(job.userId(), job.resourceId());
                yield "/app/interviews?session=" + job.resourceId();
            }
        };
    }

    private String json(Object value) { return mapper.writeValueAsString(value); }
}
