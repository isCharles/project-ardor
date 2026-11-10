package com.projectardor.learning.practice.mockinterview.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.projectardor.learning.practice.mockinterview.domain.InterviewModality;
import com.projectardor.learning.practice.mockinterview.domain.InterviewSession;
import com.projectardor.learning.practice.mockinterview.service.adaptive.AdaptiveInterviewOrchestrator;
import com.projectardor.platform.jobs.aijobs.AiJobType;
import com.projectardor.platform.jobs.aijobs.AiJobWork;

/** One queue task serves both modes, so it has to reach the state machine that owns the session. */
class MockInterviewEvaluationJobHandlerTests {

    private final UUID owner = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final InterviewService classic = mock(InterviewService.class);
    private final AdaptiveInterviewOrchestrator adaptive = mock(AdaptiveInterviewOrchestrator.class);
    private final MockInterviewEvaluationJobHandler handler =
            new MockInterviewEvaluationJobHandler(classic, adaptive);
    private final AiJobWork work = new AiJobWork(UUID.randomUUID(), owner, AiJobType.INTERVIEW_EVALUATE, sessionId, null);

    @Test void anAdaptiveSessionIsFinishedByTheAdaptiveOrchestrator() {
        when(classic.get(owner, sessionId)).thenReturn(
                InterviewSession.createAdaptive(owner, null, "Acme", "后端工程师", null, "MID", 30, false, "JD"));

        assertThat(handler.perform(work)).isEqualTo("/app/interviews?session=" + sessionId);

        verify(adaptive).finish(owner, sessionId);
        verify(classic, never()).finish(any(), any());
    }

    @Test void aClassicSessionKeepsUsingTheFixedPaperService() {
        when(classic.get(owner, sessionId)).thenReturn(
                InterviewSession.create(owner, null, InterviewModality.TEXT, null, "后端工程师", null));

        assertThat(handler.perform(work)).isEqualTo("/app/interviews?session=" + sessionId);

        verify(classic).finish(owner, sessionId);
        verifyNoInteractions(adaptive);
    }

    @Test void enqueueingStillChecksOwnershipBeforeAnyWorkIsQueued() {
        handler.validateEnqueue(owner, sessionId, null, false);

        verify(classic).get(owner, sessionId);
        verifyNoInteractions(adaptive);
    }
}