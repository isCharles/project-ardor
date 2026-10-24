package com.projectardor.learning.plan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.projectardor.scheduling.service.CalendarTaskService;
import com.projectardor.common.json.LlmJsonParser;
import com.projectardor.common.web.ResourceNotFoundException;
import com.projectardor.learning.plan.domain.LearningAttempt;
import com.projectardor.learning.plan.domain.LearningPlan;
import com.projectardor.learning.plan.domain.LearningSourceType;
import com.projectardor.learning.plan.domain.LearningStatus;
import com.projectardor.learning.plan.repository.LearningAttemptRepository;
import com.projectardor.learning.plan.repository.LearningPlanRepository;
import com.projectardor.platform.model.llm.service.LlmGateway;
import com.projectardor.platform.identity.profile.domain.UserProfile;
import com.projectardor.platform.identity.profile.service.ProfileService;

import tools.jackson.databind.ObjectMapper;

class LearningPlanServiceTests {
    private static final Instant DUE = Instant.parse("2026-10-08T01:00:00Z");
    private static final String STRONG = """
            {"score":85,"feedback":"理解到位","strengths":["内存模型清晰"],"gaps":[],
             "nextFocus":"继续练习类加载","questionFeedback":[{"question":"解释 JVM 内存","feedback":"准确"}],
             "nextExercises":[]}
            """;
    private static final String WEAK = """
            {"score":62,"feedback":"需要巩固","strengths":[],"gaps":["缺少恢复步骤"],
             "nextFocus":"持久化取舍","questionFeedback":[{"question":"解释 JVM 内存","feedback":"补充细节"}],
             "nextExercises":[]}
            """;
    private static final String WEAK_WITH_FOLLOW_UPS = """
            {"score":62,"feedback":"需要巩固","strengths":[],"gaps":["缺少恢复步骤"],
             "nextFocus":"持久化取舍","questionFeedback":[],
             "nextExercises":[
               {"question":"新的简答题","type":"SHORT_ANSWER","rubric":["要点"]},
               {"question":"新的选择题","type":"MULTIPLE_CHOICE","options":["A","B","C"],"correctOption":0,"rubric":["A"]}]}
            """;

    @Test
    void twoRoundsArePersistedAsImmutableAttemptsAndReadBack() {
        Fixture f = new Fixture();
        f.modelReturns(WEAK);
        List<Map<String, Object>> originalExercises = f.plan.getExercises();
        List<String> answers = List.of("JVM 管理内存并执行字节码", "B");

        LearningPlan first = f.service.submit(f.userId, f.plan.getId(), answers);
        assertThat(first.getAttemptCount()).isEqualTo(1);
        assertThat(first.getStatus()).isEqualTo(LearningStatus.NEEDS_REVIEW);
        f.modelReturns(STRONG);
        LearningPlan second = f.service.submit(f.userId, f.plan.getId(), answers);

        assertThat(second.getAttemptCount()).isEqualTo(2);
        assertThat(second.getStatus()).isEqualTo(LearningStatus.COMPLETED);
        assertThat(f.saved).hasSize(2);
        LearningAttempt firstAttempt = f.saved.get(0);
        assertThat(firstAttempt.getAttemptNo()).isEqualTo(1);
        assertThat(firstAttempt.getPlanId()).isEqualTo(f.plan.getId());
        assertThat(firstAttempt.getUserId()).isEqualTo(f.userId);
        assertThat(firstAttempt.isLegacy()).isFalse();
        assertThat(firstAttempt.getModelName()).isEqualTo("eval-model");
        assertThat(firstAttempt.getPromptVersion()).isEqualTo(LearningPlanService.EVALUATION_PROMPT_VERSION);
        assertThat(firstAttempt.getSubmittedAt()).isNotNull();
        assertThat(firstAttempt.getAnswers()).isEqualTo(answers);
        // The graded exercise snapshot keeps what the old truncated projection discarded.
        assertThat(firstAttempt.getExerciseSnapshot()).hasSize(2);
        assertThat(firstAttempt.getExerciseSnapshot().getFirst())
                .containsEntry("question", "解释 JVM 内存")
                .containsEntry("rubric", List.of("内存区域", "执行"))
                .containsKey("exerciseId");
        Map<String, Object> evaluation = firstAttempt.getEvaluation();
        assertThat(evaluation).containsKeys("feedback", "strengths", "gaps", "nextFocus", "questionFeedback",
                "contentVersion", "exercisesVersion");
        assertThat(evaluation.get("contentVersion")).isEqualTo(1);
        assertThat(evaluation.get("exercisesVersion")).isEqualTo(1);
        assertThat(evaluation.get("nextFocus")).isEqualTo("持久化取舍");        assertThat(storedChoices(evaluation).getFirst())
                .containsEntry("selected", "B")
                .containsEntry("correct", true);
        // The compatibility projection keeps the old shape and still never exposes nextExercises.
        @SuppressWarnings("unchecked")
        List<Object> projected = (List<Object>) f.plan.getLastEvaluation().get("attempts");
        assertThat(projected).hasSize(2);
        assertThat(projected.getFirst().toString()).doesNotContain("rubric");
        assertThat(f.plan.getLastEvaluation()).doesNotContainKey("nextExercises");
        assertThat(f.plan.getExercises()).isEqualTo(originalExercises);
        assertThat(f.plan.getExercisesVersion()).isEqualTo(1);
        assertThat(f.service.attempts(f.userId, f.plan.getId())).containsExactlyElementsOf(f.saved);
        verify(f.llm, times(2)).completeJson(any(), any(), any());
    }

    @Test
    void moreThanTwentyAttemptsAreNotTruncatedInTheNewHistory() {
        Fixture f = new Fixture();
        f.modelReturns(WEAK_WITH_FOLLOW_UPS);
        for (int round = 0; round < 21; round++) {
            final int currentRound = round;
            List<String> answers = f.plan.getExercises().stream()
                    .map(exercise -> "SHORT_ANSWER".equals(exercise.get("type")) ? "回答 " + currentRound : "A")
                    .toList();
            f.service.submit(f.userId, f.plan.getId(), answers);
        }

        assertThat(f.saved).hasSize(21);
        assertThat(f.saved.stream().map(LearningAttempt::getAttemptNo).toList())
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, 21).boxed().toList());
        assertThat(f.plan.getAttemptCount()).isEqualTo(21);
        // Only the legacy compatibility projection is truncated to the newest 20 rounds.
        @SuppressWarnings("unchecked")
        List<Object> projection = (List<Object>) f.plan.getLastEvaluation().get("attempts");
        assertThat(projection).hasSize(20);
        assertThat(f.service.attempts(f.userId, f.plan.getId())).hasSize(21);
        // Each weak round replaced the exercise set, so the version keeps increasing.
        assertThat(f.plan.getExercisesVersion()).isEqualTo(1 + 21);
        assertThat(f.saved.getLast().getEvaluation().get("exercisesVersion")).isEqualTo(21);
        verify(f.llm, times(21)).completeJson(any(), any(), any());
    }

    @Test
    void requestIdRetryReturnsTheCommittedPlanWithoutAnotherModelCall() {
        Fixture f = new Fixture();
        f.modelReturns(STRONG);
        UUID requestId = UUID.randomUUID();
        List<String> answers = List.of("JVM 管理内存并执行字节码", "B");

        LearningPlan first = f.service.submit(f.userId, f.plan.getId(), answers, 0, requestId);
        LearningPlan retry = f.service.submit(f.userId, f.plan.getId(), answers, 0, requestId);

        assertThat(first.getAttemptCount()).isEqualTo(1);
        assertThat(retry.getAttemptCount()).isEqualTo(1);
        assertThat(f.saved).hasSize(1);
        assertThat(f.saved.getFirst().getRequestId()).isEqualTo(requestId);
        verify(f.llm, times(1)).completeJson(any(), any(), any());
    }

    @Test
    void staleAttemptCountAndExerciseVersionAreRejectedBeforeAnyModelCall() {
        Fixture f = new Fixture();
        List<String> answers = List.of("JVM 管理内存并执行字节码", "B");

        assertThatThrownBy(() -> f.service.submit(f.userId, f.plan.getId(), answers, 7, null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("学习轮次已更新");
        assertThatThrownBy(() -> f.service.submit(f.userId, f.plan.getId(), answers, null, 5, null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("学习内容已更新");
        assertThatThrownBy(() -> f.service.submit(f.userId, f.plan.getId(), answers, 0, 3, null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("学习内容已更新");

        verifyNoInteractions(f.llm);
        assertThat(f.saved).isEmpty();
        assertThat(f.plan.getAttemptCount()).isZero();
    }

    @Test
    void replacedExerciseSetBumpsTheVersionAndOldAnswersCannotAttachToIt() {
        Fixture f = new Fixture();
        f.modelReturns(WEAK_WITH_FOLLOW_UPS);
        List<String> originalIds = f.plan.getExercises().stream()
                .map(exercise -> String.valueOf(exercise.get("exerciseId"))).toList();

        LearningPlan reviewed = f.service.submit(f.userId, f.plan.getId(),
                List.of("JVM 管理内存并执行字节码", "B"));

        assertThat(reviewed.getExercisesVersion()).isEqualTo(2);
        List<String> followUpIds = reviewed.getExercises().stream()
                .map(exercise -> String.valueOf(exercise.get("exerciseId"))).toList();
        assertThat(followUpIds).hasSize(2).doesNotContainAnyElementsOf(originalIds);
        // The attempt snapshot still records the exercise set that was actually answered.
        assertThat(f.saved.getFirst().getExerciseSnapshot().stream()
                .map(exercise -> String.valueOf(exercise.get("exerciseId"))).toList())
                .containsExactlyElementsOf(originalIds);
        // A delayed answer for the previous set is fenced before the second model call.
        assertThatThrownBy(() -> f.service.submit(f.userId, f.plan.getId(),
                List.of("旧回答", "旧回答"), 1, 1, null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("学习内容已更新");
        verify(f.llm, times(1)).completeJson(any(), any(), any());
    }

    @Test
    void legacyProjectionRequestIdStillPreventsDuplicateSubmission() {
        Fixture f = new Fixture();
        UUID requestId = UUID.randomUUID();
        f.plan.recordAttempt(60, Map.of("attempts",
                List.of(Map.of("requestId", requestId.toString(), "items", List.of()))), Instant.now());

        LearningPlan result = f.service.submit(f.userId, f.plan.getId(),
                List.of("JVM 管理内存并执行字节码", "B"), 1, requestId);

        assertThat(result.getAttemptCount()).isEqualTo(1);
        assertThat(f.saved).isEmpty();
        verifyNoInteractions(f.llm);
    }

    @Test
    void crossUserPlanAndHistoryAreInvisible() {
        Fixture f = new Fixture();
        UUID other = UUID.randomUUID();
        List<String> answers = List.of("JVM 管理内存并执行字节码", "B");

        assertThatThrownBy(() -> f.service.attempts(other, f.plan.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> f.service.submit(other, f.plan.getId(), answers))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(f.llm, never()).completeJson(any(), any(), any());
        assertThat(f.saved).isEmpty();
    }

    @Test
    void anomalousLegacyProjectionContinuesAfterTheHighestStoredAttemptNo() {
        Fixture f = new Fixture();
        f.modelReturns(WEAK);
        // Simulates an inconsistent pre-V52 projection: attempt_count says 1 but the legacy
        // array held 3 rounds, so V52 backfilled attempt_no 1..3 for this plan.
        f.plan.recordAttempt(60, Map.of("attempts", List.of(
                Map.of("items", List.of(), "score", 50),
                Map.of("items", List.of(), "score", 50),
                Map.of("items", List.of(), "score", 50))), Instant.now());
        f.storedMaxAttemptNo.set(3);
        List<String> answers = List.of("JVM 管理内存并执行字节码", "B");

        LearningPlan afterAnomalousHistory = f.service.submit(f.userId, f.plan.getId(), answers, 1, null);
        assertThat(afterAnomalousHistory.getAttemptCount()).isEqualTo(2);
        assertThat(f.saved).hasSize(1);
        // Attempt numbers continue after the stored maximum instead of colliding with the
        // backfilled rows (the old attempt_count + 1 allocation produced 2 and a 500 rollback).
        assertThat(f.saved.getFirst().getAttemptNo()).isEqualTo(4);
        f.modelReturns(WEAK);
        LearningPlan afterSecondRound = f.service.submit(f.userId, f.plan.getId(), answers, 2, null);

        assertThat(afterSecondRound.getAttemptCount()).isEqualTo(3);
        assertThat(f.saved).hasSize(2);
        assertThat(f.saved.get(1).getAttemptNo()).isEqualTo(5);
        assertThat(f.saved.stream().map(LearningAttempt::getAttemptNo)).doesNotHaveDuplicates();
        // History stays readable and ordered; anomalous legacy numbering is neither hidden nor rewritten.
        assertThat(f.service.attempts(f.userId, f.plan.getId()))
                .extracting(LearningAttempt::getAttemptNo).containsExactly(4, 5);
    }

    private static List<Map<String, Object>> storedChoices(Map<String, Object> evaluation) {
        assertThat(evaluation.get("choices")).isInstanceOf(List.class);
        List<Map<String, Object>> choices = new ArrayList<>();
        for (Object item : (List<?>) evaluation.get("choices")) {
            assertThat(item).isInstanceOf(Map.class);
            Map<?, ?> raw = (Map<?, ?>) item;
            Map<String, Object> choice = new LinkedHashMap<>();
            raw.forEach((key, value) -> choice.put(String.valueOf(key), value));
            choices.add(choice);
        }
        return choices;
    }

    @Test
    void applicationViewsReuseOwnedQueriesAndExistingMutationRules() {
        Fixture f = new Fixture();
        when(f.plans.findAllByUserIdOrderByScheduledAtAscCreatedAtDesc(f.userId)).thenReturn(List.of(f.plan));
        assertThat(f.service.listViews(f.userId)).singleElement().satisfies(view -> assertThat(view.id()).isEqualTo(f.plan.getId()));
        assertThat(f.service.detail(f.userId, f.plan.getId()).concept()).isEqualTo("JVM");
        assertThat(f.service.beginView(f.userId, f.plan.getId()).status()).isEqualTo(LearningStatus.IN_PROGRESS);
        f.plan.recordAttempt(62, Map.of("feedback", "keep this result"), DUE);
        Instant future = Instant.now().plusSeconds(86400);
        var updated = f.service.updateArrangementView(f.userId, f.plan.getId(), "new reason", future);
        assertThat(updated.scheduledAt()).isEqualTo(future);
        assertThat(updated.lastScore()).isEqualTo(62);
        assertThat(updated.attemptCount()).isEqualTo(1);
        assertThat(updated.lastEvaluation()).containsEntry("feedback", "keep this result");
        UUID stranger = UUID.randomUUID();
        assertThatThrownBy(() -> f.service.detail(stranger, f.plan.getId())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> f.service.beginView(stranger, f.plan.getId())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> f.service.updateArrangementView(stranger, f.plan.getId(), "other", future))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void mutationSnapshotIsTakenAfterPersistenceCallbacksHaveUpdatedTheTimestamp() {
        Fixture f = new Fixture();
        Instant first = Instant.parse("2027-01-01T00:00:00Z");
        Instant second = first.plusSeconds(1);
        org.mockito.Mockito.doAnswer(call -> {
            org.springframework.test.util.ReflectionTestUtils.setField(f.plan, "updatedAt", first);
            return null;
        }).doAnswer(call -> {
            org.springframework.test.util.ReflectionTestUtils.setField(f.plan, "updatedAt", second);
            return null;
        }).when(f.plans).flush();
        assertThat(f.service.beginView(f.userId, f.plan.getId()).updatedAt()).isEqualTo(first);
        assertThat(f.service.updateArrangementView(f.userId, f.plan.getId(), "updated", null).updatedAt()).isEqualTo(second);
        verify(f.plans, times(2)).flush();
    }

    private static class Fixture {
        final UUID userId = UUID.randomUUID();
        final LearningPlanRepository plans = mock(LearningPlanRepository.class);
        final LearningAttemptRepository attempts = mock(LearningAttemptRepository.class);
        final LlmGateway llm = mock(LlmGateway.class);
        final ProfileService profiles = mock(ProfileService.class);
        final List<LearningAttempt> saved = new ArrayList<>();
        final Map<UUID, LearningAttempt> byRequest = new HashMap<>();
        final java.util.concurrent.atomic.AtomicInteger storedMaxAttemptNo =
                new java.util.concurrent.atomic.AtomicInteger();
        final LearningPlan plan;
        final LearningPlanService service;

        Fixture() {
            plan = LearningPlan.create(userId, "JVM", null, LearningSourceType.MANUAL, null,
                    lesson(), exercises(), DUE, "generation-model");
            when(plans.findByIdAndUserId(plan.getId(), userId)).thenReturn(Optional.of(plan));
            when(plans.findOwnedForUpdate(plan.getId(), userId)).thenReturn(Optional.of(plan));
            when(plans.save(any(LearningPlan.class))).thenAnswer(call -> call.getArgument(0));
            when(attempts.maxAttemptNo(any(), any())).thenAnswer(call -> storedMaxAttemptNo.get());
            when(attempts.findByUserIdAndRequestId(any(), any())).thenAnswer(call -> {
                UUID requestId = call.getArgument(1);
                return Optional.ofNullable(requestId == null ? null : byRequest.get(requestId));
            });
            when(attempts.save(any(LearningAttempt.class))).thenAnswer(call -> {
                LearningAttempt attempt = call.getArgument(0);
                saved.add(attempt);
                storedMaxAttemptNo.accumulateAndGet(attempt.getAttemptNo(), Math::max);
                if (attempt.getRequestId() != null) byRequest.put(attempt.getRequestId(), attempt);
                return attempt;
            });
            when(attempts.findAllByUserIdAndPlanIdOrderByAttemptNoAsc(any(), any()))
                    .thenAnswer(call -> List.copyOf(saved));
            when(profiles.view(userId)).thenReturn(com.projectardor.platform.identity.profile.service.ProfileView.from(UserProfile.create(userId, "Learning Owner")));
            PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
            when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
            ObjectMapper mapper = new ObjectMapper();
            service = new LearningPlanService(plans, attempts, llm, new LlmJsonParser(mapper), mapper,
                    mock(CalendarTaskService.class), profiles, mock(LearningContextResolver.class),
                    mock(LearningPlanCreationStore.class), mock(LearningPlanGenerationGate.class),
                    mock(LearningPlanAgentSlots.class), transactions);
        }

        void modelReturns(String content) {
            when(llm.completeJson(any(), any(), any()))
                    .thenReturn(new LlmGateway.LlmResult(content, "eval-model"));
        }
    }

    private static Map<String, Object> lesson() {
        Map<String, Object> lesson = new LinkedHashMap<>();
        lesson.put("summary", "JVM 基础");
        lesson.put("keyPoints", List.of("内存区域", "执行流程"));
        lesson.put("explanation", "讲解");
        return lesson;
    }

    private static List<Map<String, Object>> exercises() {
        Map<String, Object> shortAnswer = new LinkedHashMap<>();
        shortAnswer.put("exerciseId", UUID.randomUUID().toString());
        shortAnswer.put("question", "解释 JVM 内存");
        shortAnswer.put("type", "SHORT_ANSWER");
        shortAnswer.put("rubric", List.of("内存区域", "执行"));
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("exerciseId", UUID.randomUUID().toString());
        choice.put("question", "选择持久化方式");
        choice.put("type", "MULTIPLE_CHOICE");
        choice.put("options", List.of("A", "B", "C"));
        choice.put("correctOption", 1);
        choice.put("rubric", List.of("B"));
        return List.of(shortAnswer, choice);
    }
}
