package com.projectardor.aijobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.projectardor.learning.domain.LearningPlan;
import com.projectardor.learning.service.LearningPlanService;
import com.projectardor.learning.service.LearningEvaluationJobHandler;
import tools.jackson.databind.ObjectMapper;

class AiJobServiceTests {
    @Test void workerUsesPersistedOwnerRoundAndLogicalRequestId() {
        UUID user = UUID.randomUUID(), planId = UUID.randomUUID(), request = UUID.randomUUID();
        var learning = mock(LearningPlanService.class);
        var plan = mock(LearningPlan.class);
        when(plan.getId()).thenReturn(planId);
        when(learning.submit(eq(user), eq(planId), anyList(), eq(2), eq(request))).thenReturn(plan);
        var mapper=new ObjectMapper();
        var service = new AiJobService(mock(AiJobStore.class), handlers(new LearningEvaluationJobHandler(learning,mapper)), mapper);
        String payload = "{\"requestId\":\"" + UUID.randomUUID() + "\",\"expectedAttemptCount\":2,\"answers\":[\"saved answer\"]}";
        var job = new AiJobStore.Job(request, user, AiJobType.LEARNING_EVALUATE, planId, "hash", payload,
                "RUNNING", 1, null, null, null, Instant.now(), Instant.now(), null);
        assertThat(service.perform(job)).isEqualTo("/app/learning?id=" + planId);
        verify(learning).submit(user, planId, List.of("saved answer"), 2, request);
        assertThat(AiJobResponse.from(job).estimateMaxSeconds()).isGreaterThan(AiJobResponse.from(job).estimateMinSeconds());
    }

    @Test void duplicateAndMissingHandlersFailAtRegistrationRatherThanAtRuntime() {
        var handler=mock(AiJobHandler.class);when(handler.type()).thenReturn(AiJobType.LEARNING_CREATE);
        assertThatThrownBy(() -> new AiJobService(mock(AiJobStore.class),List.of(handler,handler),new ObjectMapper()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Duplicate");
        assertThatThrownBy(() -> new AiJobService(mock(AiJobStore.class),List.of(handler),new ObjectMapper()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Missing");
    }

    @Test void existingEvaluationStillChecksOwnerButDoesNotRejectTheAlreadyAdvancedAttempt() {
        var learning=mock(LearningPlanService.class);var plan=mock(LearningPlan.class);
        UUID owner=UUID.randomUUID(),id=UUID.randomUUID();
        when(learning.get(owner,id)).thenReturn(plan);when(plan.getAttemptCount()).thenReturn(3);
        var handler=new LearningEvaluationJobHandler(learning,new ObjectMapper());
        String payload="{\"expectedAttemptCount\":2,\"answers\":[\"saved\"]}";
        handler.validateEnqueue(owner,id,payload,true);
        assertThatThrownBy(() -> handler.validateEnqueue(owner,id,payload,false)).isInstanceOf(IllegalStateException.class);
        verify(learning,times(2)).get(owner,id);
        verify(learning,never()).submit(any(),any(),any(),anyInt(),any());
    }

    private List<AiJobHandler> handlers(AiJobHandler replacement) {
        return java.util.Arrays.stream(AiJobType.values()).map(type -> {
            if (type == replacement.type()) return replacement;
            var handler=mock(AiJobHandler.class);when(handler.type()).thenReturn(type);return handler;
        }).toList();
    }
}
