package com.projectardor.learning.plan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.projectardor.scheduling.service.CalendarTaskService;
import com.projectardor.common.json.LlmJsonParser;
import com.projectardor.learning.plan.domain.LearningPlan;
import com.projectardor.learning.plan.domain.LearningSourceType;
import com.projectardor.learning.plan.repository.LearningPlanRepository;
import com.projectardor.platform.model.llm.service.LlmGateway;
import com.projectardor.platform.identity.profile.service.ProfileService;
import com.projectardor.jobsearch.record.repository.InterviewRecapQuestionRepository;
import com.projectardor.jobsearch.record.repository.InterviewRecapRepository;

import tools.jackson.databind.ObjectMapper;

class LearningFeedbackHistoryTests {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void submittedAnswerAndQuestionFeedbackAreKeptTogetherWithoutRubric(boolean identifiedFeedback) {
        UUID userId = UUID.randomUUID();
        LearningPlan plan = LearningPlan.create(userId, "JVM", null, LearningSourceType.MANUAL,
                null, Map.of("summary", "JVM basics"),
                List.of(Map.of("exerciseId", "exercise-one", "question", "介绍 JVM", "type", "SHORT_ANSWER",
                        "rubric", List.of("内存", "执行"))),
                Instant.parse("2026-10-06T01:00:00Z"), "test-model");
        LearningPlanRepository repository = mock(LearningPlanRepository.class);
        LlmGateway llm = mock(LlmGateway.class);
        when(repository.findByIdAndUserId(plan.getId(), userId)).thenReturn(Optional.of(plan));
        when(repository.findOwnedForUpdate(plan.getId(), userId)).thenReturn(Optional.of(plan));
        when(repository.save(any(LearningPlan.class))).thenAnswer(call -> call.getArgument(0));
        when(llm.completeJson(any(), any(), any())).thenReturn(new LlmGateway.LlmResult("""
                {"score":85,"feedback":"理解到位","strengths":["内存模型"],"gaps":["类加载"],"nextFocus":"复习加载",
                 "questionFeedback":[{%s"question":"介绍 JVM","feedback":"内存区域说明准确，补充类加载即可。"}],
                 "nextExercises":[]}
                """.formatted(identifiedFeedback ? "\"exerciseId\":\"exercise-one\"," : ""), "test-model"));
        ObjectMapper mapper = new ObjectMapper();
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        LearningPlanService service = new LearningPlanService(repository,
                mock(com.projectardor.learning.plan.repository.LearningAttemptRepository.class), llm,
                new LlmJsonParser(mapper), mapper, mock(CalendarTaskService.class),
                mock(ProfileService.class), mock(LearningContextResolver.class),
                mock(LearningPlanCreationStore.class),
                mock(LearningPlanGenerationGate.class), mock(LearningPlanAgentSlots.class), transactionManager);

        LearningPlan updated = service.submit(userId, plan.getId(), List.of("JVM 管理内存并执行字节码"));

        @SuppressWarnings("unchecked")
        Map<String, Object> attempt = (Map<String, Object>) ((List<?>) updated.getLastEvaluation()
                .get("attempts")).getFirst();
        assertThat(attempt.get("questionFeedback")).isEqualTo(List.of(identifiedFeedback
                ? Map.of("exerciseId", "exercise-one", "question", "介绍 JVM", "feedback", "内存区域说明准确，补充类加载即可。")
                : Map.of("question", "介绍 JVM", "feedback", "内存区域说明准确，补充类加载即可。")));
        assertThat(attempt).containsEntry("strengths", List.of("内存模型"))
                .containsEntry("gaps", List.of("类加载")).containsEntry("nextFocus", "复习加载");
        assertThat(attempt.get("items").toString()).contains("JVM 管理内存并执行字节码")
                .contains("exerciseId=exercise-one").doesNotContain("rubric");
        assertThat(updated.getLastEvaluation()).doesNotContainKey("nextExercises");
    }
}
