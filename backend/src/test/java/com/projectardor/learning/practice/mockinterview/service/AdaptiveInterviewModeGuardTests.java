package com.projectardor.learning.practice.mockinterview.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import com.projectardor.common.json.LlmJsonParser;
import com.projectardor.jobsearch.interview.service.OpportunityService;
import com.projectardor.learning.practice.mockinterview.domain.InterviewEvaluation;
import com.projectardor.learning.practice.mockinterview.domain.InterviewModality;
import com.projectardor.learning.practice.mockinterview.domain.InterviewMode;
import com.projectardor.learning.practice.mockinterview.domain.InterviewSession;
import com.projectardor.learning.practice.mockinterview.domain.InterviewStatus;
import com.projectardor.learning.practice.mockinterview.repository.InterviewAnswerRepository;
import com.projectardor.learning.practice.mockinterview.repository.InterviewEvaluationRepository;
import com.projectardor.learning.practice.mockinterview.repository.InterviewQuestionRepository;
import com.projectardor.learning.practice.mockinterview.repository.InterviewSessionRepository;
import com.projectardor.materials.resume.service.ResumeService;
import com.projectardor.platform.identity.profile.service.ProfileService;
import com.projectardor.platform.model.llm.service.LlmGateway;

import tools.jackson.databind.json.JsonMapper;

/**
 * The fixed-paper entry points are also reachable from the Agent tools, so they must refuse an
 * adaptive session rather than save an unscored answer into a state machine that owns its turns.
 */
class AdaptiveInterviewModeGuardTests {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final UUID owner = UUID.randomUUID();
    private final InterviewSessionRepository sessions = mock(InterviewSessionRepository.class);
    private final InterviewQuestionRepository questions = mock(InterviewQuestionRepository.class);
    private final InterviewAnswerRepository answers = mock(InterviewAnswerRepository.class);
    private final InterviewEvaluationRepository evaluations = mock(InterviewEvaluationRepository.class);
    private final LlmGateway llm = mock(LlmGateway.class);
    private final InterviewService service = new InterviewService(sessions, questions, answers, evaluations,
            mock(ProfileService.class), mock(ResumeService.class), llm, new LlmJsonParser(mapper), mapper,
            mock(PlatformTransactionManager.class), mock(LeetCodeHot100.class), mock(OpportunityService.class));

    private InterviewSession adaptive() {
        return InterviewSession.createAdaptive(owner, null, "Acme", "Backend", null, "MID", 30, false, "JD");
    }

    @Test void theClassicListHidesAdaptiveSessions() {
        var classic = InterviewSession.create(owner, null, InterviewModality.TEXT, null, "Backend", null);
        var adaptive = adaptive();
        when(sessions.findAllByUserIdOrderByCreatedAtDesc(owner)).thenReturn(List.of(adaptive, classic));
        assertThat(service.list(owner)).containsExactly(classic);
        assertThat(service.listViews(owner)).extracting(MockInterviewSessionView::id).containsExactly(classic.getId());
    }

    @Test void theClassicNextQuestionRefusesBeforeReadingAnyTurn() {
        var adaptive = adaptive();
        when(sessions.findByIdAndUserId(adaptive.getId(), owner)).thenReturn(Optional.of(adaptive));
        assertThatThrownBy(() -> service.getNextQuestion(owner, adaptive.getId()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("自适应");
        verifyNoInteractions(questions, answers, llm);
    }

    @Test void theClassicAnswerPathRefusesWithoutSavingAnUnscoredTurn() {
        var adaptive = adaptive();
        when(sessions.findOwnedForUpdate(adaptive.getId(), owner)).thenReturn(Optional.of(adaptive));
        assertThatThrownBy(() -> service.submitAnswer(owner, adaptive.getId(), UUID.randomUUID(), "Answer", 10))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("自适应");
        verify(answers, never()).save(any());
        verifyNoInteractions(questions, llm);
    }

    @Test void theClassicFinishRefusesWithoutSpendingAModelCall() {
        var adaptive = adaptive();
        when(sessions.findByIdAndUserId(adaptive.getId(), owner)).thenReturn(Optional.of(adaptive));
        when(evaluations.findByInterviewSessionIdAndUserId(adaptive.getId(), owner)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.finish(owner, adaptive.getId()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("自适应");
        verifyNoInteractions(llm, questions, answers);
        verify(evaluations, never()).save(any());
        assertThat(adaptive.getStatus()).isEqualTo(InterviewStatus.IN_PROGRESS);
    }

    @Test void lifecycleAndReportReadsStaySharedBetweenBothModes() {
        var adaptive = adaptive();
        var report = InterviewEvaluation.create(owner, adaptive.getId(), new BigDecimal("72.00"),
                Map.of("overall", Map.of("jobFit", "MATCH")), "fixture", "adaptive-interview-report-v1");
        when(sessions.findByIdAndUserId(adaptive.getId(), owner)).thenReturn(Optional.of(adaptive));
        when(sessions.findOwnedForUpdate(adaptive.getId(), owner)).thenReturn(Optional.of(adaptive));
        when(sessions.save(adaptive)).thenReturn(adaptive);
        when(evaluations.findByInterviewSessionIdAndUserId(adaptive.getId(), owner)).thenReturn(Optional.of(report));

        assertThat(service.get(owner, adaptive.getId()).getMode()).isEqualTo(InterviewMode.ADAPTIVE);
        assertThat(service.evaluationView(owner, adaptive.getId()).promptVersion())
                .isEqualTo("adaptive-interview-report-v1");
        assertThat(service.cancelView(owner, adaptive.getId()).status()).isEqualTo(InterviewStatus.CANCELLED);
        verifyNoInteractions(llm);
    }
}