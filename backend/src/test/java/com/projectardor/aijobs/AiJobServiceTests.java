package com.projectardor.aijobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.projectardor.learning.domain.LearningPlan;
import com.projectardor.learning.service.LearningPlanService;
import com.projectardor.recap.service.InterviewReplayService;
import com.projectardor.interview.service.InterviewService;
import tools.jackson.databind.ObjectMapper;

class AiJobServiceTests {
    @Test void workerUsesPersistedOwnerRoundAndLogicalRequestId() {
        UUID user = UUID.randomUUID(), planId = UUID.randomUUID(), request = UUID.randomUUID();
        var learning = mock(LearningPlanService.class);
        var plan = mock(LearningPlan.class);
        when(plan.getId()).thenReturn(planId);
        when(learning.submit(eq(user), eq(planId), anyList(), eq(2), eq(request))).thenReturn(plan);
        var service = new AiJobService(mock(AiJobStore.class), learning,
                mock(InterviewReplayService.class), mock(InterviewService.class), new ObjectMapper());
        String payload = "{\"requestId\":\"" + request + "\",\"expectedAttemptCount\":2,\"answers\":[\"saved answer\"]}";
        var job = new AiJobStore.Job(request, user, AiJobType.LEARNING_EVALUATE, planId, "hash", payload,
                "RUNNING", 1, null, null, null, Instant.now(), Instant.now(), null);
        assertThat(service.perform(job)).isEqualTo("/app/learning?id=" + planId);
        verify(learning).submit(user, planId, List.of("saved answer"), 2, request);
        assertThat(AiJobResponse.from(job).estimateMaxSeconds()).isGreaterThan(AiJobResponse.from(job).estimateMinSeconds());
    }
}
