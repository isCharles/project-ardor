package com.projectardor.learning.plan.service;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.projectardor.learning.plan.domain.LearningOriginKind;
import com.projectardor.learning.plan.domain.LearningPlan;
import com.projectardor.learning.plan.domain.LearningSourceType;
import com.projectardor.learning.plan.domain.LearningStatus;
import com.projectardor.learning.plan.web.LearningPlanResponse;
import tools.jackson.databind.json.JsonMapper;

class LearningPlanViewTests {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Instant due = Instant.parse("2027-01-01T01:00:00Z");

    @Test void legacyJsonShapeAndExerciseProjectionAreExactlyPreserved() {
        UUID request = UUID.randomUUID(), source = UUID.randomUUID();
        var exercise = new LinkedHashMap<String, Object>();
        exercise.put("question", "原始练习");
        exercise.put("options", new ArrayList<>(Arrays.asList("A", null, 3)));
        exercise.put("rubric", List.of("internal rubric"));
        exercise.put("correctOption", 0);
        var plan = LearningPlan.create(UUID.randomUUID(), request, "fixture-hash", "学习主题", null,
                LearningSourceType.RECAP, source, Map.of("summary", "讲解"), List.of(exercise), due, "fixture");
        var expected = new LearningPlanResponse(plan.getId(), request, "学习主题", null,
                LearningSourceType.RECAP, source, LearningStatus.SCHEDULED, Map.of("summary", "讲解"),
                List.of(new LearningPlanResponse.ExerciseResponse(null, "原始练习", "SHORT_ANSWER", List.of("A", "null", "3"))),
                Map.of(), null, 0, due, null, null, null, null, LearningOriginKind.LEGACY_RECAP,
                null, null, null, null, 1, 1);
        var view = LearningPlanView.from(plan);
        assertThat(mapper.writeValueAsString(view)).isEqualTo(mapper.writeValueAsString(expected));
        assertThat(mapper.writeValueAsString(LearningPlanResponse.from(view))).isEqualTo(mapper.writeValueAsString(expected));
        assertThat(mapper.writeValueAsString(LearningPlanResponse.from(plan))).isEqualTo(mapper.writeValueAsString(expected));
        assertThat(mapper.readTree(mapper.writeValueAsString(view)).propertyNames()).containsExactly(
                "id", "requestId", "concept", "reason", "sourceType", "sourceId", "status", "lesson", "exercises",
                "lastEvaluation", "lastScore", "attemptCount", "scheduledAt", "nextReviewAt", "completedAt", "createdAt",
                "updatedAt", "originKind", "originResourceId", "originRecordId", "targetInterviewId", "targetRoundId",
                "contentVersion", "exercisesVersion");
        assertThat(mapper.readTree(mapper.writeValueAsString(view)).path("exercises").get(0).propertyNames())
                .containsExactly("exerciseId", "question", "type", "options");
    }

    @Test void originTargetVersionsAndAttemptProjectionRemainIndependent() {
        var plan = plan(Map.of("summary", "fixture"), List.of());
        UUID question = UUID.randomUUID(), record = UUID.randomUUID(), target = UUID.randomUUID(), round = UUID.randomUUID();
        plan.assignContext(LearningOriginKind.INTERVIEW_QUESTION, question, record, target, round);
        plan.replaceExercises(List.of(Map.of("exerciseId", "version-two", "question", "New exercise")));
        plan.recordAttempt(72, Map.of("feedback", "真实反馈"), due.plusSeconds(86400));
        var view = LearningPlanView.from(plan);
        assertThat(view.originKind()).isEqualTo(LearningOriginKind.INTERVIEW_QUESTION);
        assertThat(view.originResourceId()).isEqualTo(question);
        assertThat(view.originRecordId()).isEqualTo(record);
        assertThat(view.targetInterviewId()).isEqualTo(target);
        assertThat(view.targetRoundId()).isEqualTo(round);
        assertThat(view.exercisesVersion()).isEqualTo(plan.getExercisesVersion());
        assertThat(view.contentVersion()).isEqualTo(plan.getContentVersion());
        assertThat(view.lastScore()).isEqualTo(72);
        assertThat(view.attemptCount()).isEqualTo(1);
        assertThat(view.exercises().getFirst().exerciseId()).isEqualTo("version-two");
    }

    @Test @SuppressWarnings("unchecked")
    void snapshotDoesNotAliasNestedJsonOrExposeMutableCollections() {
        var points = new ArrayList<Object>(Arrays.asList("original", null));
        var nested = new LinkedHashMap<String, Object>();
        nested.put("points", points); nested.put("nullable", null);
        var feedback = new ArrayList<Object>(Arrays.asList("feedback", null));
        var plan = plan(Map.of("section", nested), List.of(Map.of("question", "fixture", "options", List.of("A"))));
        plan.recordAttempt(60, Map.of("details", feedback), due);
        var view = LearningPlanView.from(plan);
        points.set(0, "changed"); feedback.set(0, "changed"); nested.put("new", true);
        var section = (Map<String, Object>) view.lesson().get("section");
        assertThat((List<Object>) section.get("points")).containsExactly("original", null);
        assertThat(section).containsEntry("nullable", null).doesNotContainKey("new");
        assertThat((List<Object>) view.lastEvaluation().get("details")).containsExactly("feedback", null);
        assertThatThrownBy(() -> section.put("new", true)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((List<Object>) section.get("points")).add("new")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> view.exercises().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> view.exercises().getFirst().options().add("B")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void sourceLessAutonomousPlanDoesNotAcquireInterviewParents() {
        var view = LearningPlanView.from(plan(Map.of(), List.of()));
        assertThat(view.originKind()).isEqualTo(LearningOriginKind.MANUAL);
        assertThat(view.originResourceId()).isNull();
        assertThat(view.originRecordId()).isNull();
        assertThat(view.targetInterviewId()).isNull();
        assertThat(view.targetRoundId()).isNull();
    }

    private LearningPlan plan(Map<String, Object> lesson, List<Map<String, Object>> exercises) {
        return LearningPlan.create(UUID.randomUUID(), "fixture", null, LearningSourceType.MANUAL, null, lesson, exercises, due, "fixture");
    }
}
