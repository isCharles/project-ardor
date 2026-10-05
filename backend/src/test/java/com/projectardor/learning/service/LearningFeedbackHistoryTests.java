package com.projectardor.learning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.projectardor.calendar.service.CalendarTaskService;
import com.projectardor.common.json.LlmJsonParser;
import com.projectardor.learning.domain.LearningPlan;
import com.projectardor.learning.domain.LearningSourceType;
import com.projectardor.learning.repository.LearningPlanRepository;
import com.projectardor.llm.service.LlmGateway;
import com.projectardor.profile.service.ProfileService;
import com.projectardor.recap.repository.InterviewRecapQuestionRepository;
import com.projectardor.recap.repository.InterviewRecapRepository;

import tools.jackson.databind.ObjectMapper;

class LearningFeedbackHistoryTests {
    @Test
    void submittedAnswerAndQuestionFeedbackAreKeptTogetherWithoutRubric() {
        UUID userId = UUID.randomUUID();
        LearningPlan plan = LearningPlan.create(userId, "JVM", null, LearningSourceType.MANUAL,
                null, Map.of("summary", "JVM basics"),
                List.of(Map.of("question", "介绍 JVM", "type", "SHORT_ANSWER",
                        "rubric", List.of("内存", "执行"))),
                Instant.parse("2026-10-06T01:00:00Z"), "test-model");
        LearningPlanRepository repository = mock(LearningPlanRepository.class);
        LlmGateway llm = mock(LlmGateway.class);
        when(repository.findByIdAndUserId(plan.getId(), userId)).thenReturn(Optional.of(plan));
        when(repository.save(any(LearningPlan.class))).thenAnswer(call -> call.getArgument(0));
        when(llm.completeJson(any(), any(), any())).thenReturn(new LlmGateway.LlmResult("""
                {"score":85,"feedback":"理解到位","questionFeedback":[
                  {"question":"介绍 JVM","feedback":"内存区域说明准确，补充类加载即可。"}],
                 "nextExercises":[]}
                """, "test-model"));
        ObjectMapper mapper = new ObjectMapper();
        LearningPlanService service = new LearningPlanService(repository, llm,
                new LlmJsonParser(mapper), mapper, mock(CalendarTaskService.class),
                mock(ProfileService.class), mock(InterviewRecapRepository.class),
                mock(InterviewRecapQuestionRepository.class));

        LearningPlan updated = service.submit(userId, plan.getId(), List.of("JVM 管理内存并执行字节码"));

        @SuppressWarnings("unchecked")
        Map<String, Object> attempt = (Map<String, Object>) ((List<?>) updated.getLastEvaluation()
                .get("attempts")).getFirst();
        assertThat(attempt.get("questionFeedback")).isEqualTo(List.of(
                Map.of("question", "介绍 JVM", "feedback", "内存区域说明准确，补充类加载即可。")));
        assertThat(attempt.get("items").toString()).contains("JVM 管理内存并执行字节码")
                .doesNotContain("rubric");
        assertThat(updated.getLastEvaluation()).doesNotContainKey("nextExercises");
    }
}
