package com.projectardor.learning.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import com.projectardor.calendar.service.CalendarTaskService;
import com.projectardor.common.json.LlmJsonParser;
import com.projectardor.common.web.ResourceNotFoundException;
import com.projectardor.learning.domain.*;
import com.projectardor.learning.repository.LearningPlanRepository;
import com.projectardor.llm.service.LlmGateway;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class LearningArrangementTests {
    @Test void beginUsesTheSameOwnerRowLockAsArrangementEdits() {
        Fixture f = new Fixture();
        f.service.begin(f.user, f.plan.getId());
        verify(f.repository).findOwnedForUpdate(f.plan.getId(), f.user);
        verify(f.repository, never()).findByIdAndUserId(any(), any());
    }
    @Test void changesScheduleAndReasonWithoutReplacingLessonOrHistory() {
        Fixture f = new Fixture();
        f.plan.recordAttempt(60, Map.of("feedback", "Keep feedback"), Instant.now().plusSeconds(3600));
        var lesson = f.plan.getLesson(); var exercises = f.plan.getExercises(); var evaluation = f.plan.getLastEvaluation();
        Instant date = Instant.now().plusSeconds(7200);
        var result = f.service.updateArrangement(f.user, f.plan.getId(), "New reason", date);
        assertThat(result.getNextReviewAt()).isEqualTo(date);
        assertThat(result.getScheduledAt()).isEqualTo(date);
        assertThat(result.getReason()).isEqualTo("New reason");
        assertThat(result.getLesson()).isEqualTo(lesson);
        assertThat(result.getExercises()).isEqualTo(exercises);
        assertThat(result.getLastEvaluation()).isEqualTo(evaluation);
        assertThat(result.getAttemptCount()).isEqualTo(1);
        verify(f.calendar).refreshLearningPlan(eq(f.user), eq(f.plan.getId()), eq("JVM"), eq("New reason"), eq(date), any(), eq(false));
        verifyNoInteractions(f.llm);
    }
    @Test void foreignInvalidAndCompletedUpdatesCannotMutateThePlan() {
        Fixture f = new Fixture();
        assertThatThrownBy(() -> f.service.updateArrangement(UUID.randomUUID(), f.plan.getId(), "x", null)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> f.service.updateArrangement(f.user, f.plan.getId(), null, Instant.now().minusSeconds(60))).isInstanceOf(IllegalArgumentException.class);
        assertThat(f.plan.getReason()).isEqualTo("Original reason");
        f.plan.recordAttempt(90, Map.of("feedback", "Completed"), null);
        assertThatThrownBy(() -> f.service.updateArrangement(f.user, f.plan.getId(), "x", Instant.now().plusSeconds(60))).isInstanceOf(IllegalArgumentException.class);
        assertThat(f.plan.getReason()).isEqualTo("Original reason");
        assertThat(f.service.updateArrangement(f.user, f.plan.getId(), "", null).getReason()).isNull();
        assertThat(f.plan.getLastScore()).isEqualTo(90);
    }
    @Test void assessmentDoesNotOverwriteManualReschedulingDuringTheModelCall() {
        Fixture f = new Fixture();
        Instant manual = Instant.now().plusSeconds(86400 * 5);
        when(f.llm.completeJson(any(), any(), any())).thenAnswer(call -> {
            f.service.updateArrangement(f.user, f.plan.getId(), null, manual);
            return new LlmGateway.LlmResult("{\"score\":60,\"feedback\":\"Review again\",\"nextExercises\":[]}", "fake");
        });
        var result = f.service.submit(f.user, f.plan.getId(), List.of("Synthetic answer"));
        assertThat(result.getNextReviewAt()).isEqualTo(manual);
        assertThat(result.getLastScore()).isEqualTo(60);
    }
    private static class Fixture {
        final UUID user = UUID.randomUUID();
        final LearningPlan plan = LearningPlan.create(user, "JVM", "Original reason", LearningSourceType.MANUAL, null,
                Map.of("summary", "Lesson"), List.of(Map.of("question", "JVM?", "rubric", List.of("Memory"))), Instant.now().plusSeconds(600), "fake");
        final LearningPlanRepository repository = mock(LearningPlanRepository.class);
        final LlmGateway llm = mock(LlmGateway.class);
        final CalendarTaskService calendar = mock(CalendarTaskService.class);
        final LearningPlanService service;
        Fixture() {
            when(repository.findOwnedForUpdate(plan.getId(), user)).thenReturn(Optional.of(plan));
            when(repository.findByIdAndUserId(plan.getId(), user)).thenReturn(Optional.of(plan));
            when(repository.save(any(LearningPlan.class))).thenAnswer(call -> call.getArgument(0));
            ObjectMapper mapper = new ObjectMapper();
            PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
            when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
            service = new LearningPlanService(repository, llm, new LlmJsonParser(mapper), mapper, calendar,
                mock(com.projectardor.profile.service.ProfileService.class), mock(com.projectardor.recap.service.InterviewRecordSourceAccess.class), mock(com.projectardor.resume.repository.ResumeRepository.class),
                mock(com.projectardor.knowledge.repository.KnowledgeDocumentRepository.class), mock(LearningPlanCreationStore.class),
                mock(LearningPlanGenerationGate.class), mock(LearningPlanAgentSlots.class), transactions);
        }
    }
}
