package com.projectardor.learning.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class LearningPlanTests {

    private LearningPlan plan() {
        return LearningPlan.create(UUID.randomUUID(), "JVM", "面试回答不完整", LearningSourceType.RECAP,
                UUID.randomUUID(), Map.of("summary", "掌握 JVM 运行机制"),
                List.of(Map.of("question", "介绍 JVM", "rubric", List.of("内存", "执行"))),
                Instant.parse("2026-09-09T01:00:00Z"), "test-model");
    }

    @Test
    void weakAttemptSchedulesOneFollowUp() {
        LearningPlan plan = plan();
        Instant followUp = Instant.parse("2026-09-10T01:00:00Z");

        plan.recordAttempt(62, Map.of("feedback", "内存模型需要巩固"), followUp);

        assertThat(plan.getStatus()).isEqualTo(LearningStatus.NEEDS_REVIEW);
        assertThat(plan.getScheduledAt()).isEqualTo(followUp);
        assertThat(plan.getNextReviewAt()).isEqualTo(followUp);
        assertThat(plan.getAttemptCount()).isEqualTo(1);
    }

    @Test
    void strongAttemptCompletesPlan() {
        LearningPlan plan = plan();

        plan.recordAttempt(86, Map.of("feedback", "理解到位"), null);

        assertThat(plan.getStatus()).isEqualTo(LearningStatus.COMPLETED);
        assertThat(plan.getCompletedAt()).isNotNull();
        assertThat(plan.getNextReviewAt()).isNull();
    }

    @Test
    void weakAttemptMovesToTargetedQuestionsWithoutDiscardingFeedback() {
        LearningPlan plan = plan();
        List<Map<String, Object>> nextQuestions = List.of(
                Map.of("question", "JVM 何时触发类初始化？", "type", "MULTIPLE_CHOICE",
                        "options", List.of("创建实例", "声明变量", "导入包"), "rubric", List.of("创建实例")),
                Map.of("question", "结合项目说明一次 GC 排查", "type", "SCENARIO",
                        "rubric", List.of("定位步骤")));
        Instant followUp = Instant.parse("2026-09-10T01:00:00Z");

        plan.recordAttempt(62, Map.of("feedback", "还需练习", "attempts", List.of("submitted")), followUp,
                nextQuestions);

        assertThat(plan.getExercises()).isEqualTo(nextQuestions);
        assertThat(plan.getLastEvaluation().get("attempts")).isEqualTo(List.of("submitted"));
        assertThat(plan.getStatus()).isEqualTo(LearningStatus.NEEDS_REVIEW);
    }
}
