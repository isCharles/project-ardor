package com.projectardor.learning.practice.mockinterview.domain;

import static org.assertj.core.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The rubric is the contract the evaluator is allowed to read. If the model could write the band
 * text, or if a reload silently substituted today's bands, a score would stop meaning what it meant
 * when the question was asked.
 */
class AdaptiveRubricTests {

    private AdaptiveRubric rubric() {
        return AdaptiveRubric.create("索引失效", 3, List.of("最左前缀原则", "回表代价"),
                List.of("能对比覆盖索引"), List.of("认为加索引一定更快"));
    }

    @Test void theScoreBandsAreWrittenByJavaAndThereAreAlwaysFive() {
        assertThat(rubric().scoreBands()).containsExactlyElementsOf(AdaptiveRubric.SCORE_BANDS);
        assertThat(AdaptiveRubric.SCORE_BANDS).hasSize(5);
        assertThat(AdaptiveRubric.DIFFICULTY_LEVELS).hasSize(5);
    }

    @Test void aRubricWithoutExpectedPointsCannotScoreAnything() {
        assertThatThrownBy(() -> AdaptiveRubric.create("索引失效", 3, List.of(), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("必答要点");
        assertThatThrownBy(() -> AdaptiveRubric.create("索引失效", 3, List.of("  "), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("必答要点");
    }

    @Test void aRubricRefusesADifficultyOutsideTheOneToFiveScale() {
        assertThatThrownBy(() -> AdaptiveRubric.create("索引失效", 0, List.of("要点"), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("难度");
        assertThatThrownBy(() -> AdaptiveRubric.create("索引失效", 6, List.of("要点"), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("难度");
    }

    @Test void repeatedExpectedPointsAreCollapsedSoCoverageIndicesStayUnambiguous() {
        var rubric = AdaptiveRubric.create("索引失效", 3,
                List.of("最左前缀原则", " 最左前缀原则 ", "回表代价"), List.of(), List.of());
        assertThat(rubric.expectedPoints()).containsExactly("最左前缀原则", "回表代价");
    }

    @Test void tooManyExpectedPointsAreRefusedRatherThanTruncated() {
        List<String> points = new ArrayList<>();
        for (int index = 0; index <= AdaptiveRubric.MAX_EXPECTED_POINTS; index++) points.add("要点" + index);
        assertThatThrownBy(() -> AdaptiveRubric.create("索引失效", 3, points, List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("最多");
    }

    @Test void aRubricSurvivesTheJsonbRoundTrip() {
        AdaptiveRubric original = rubric();
        AdaptiveRubric reloaded = AdaptiveRubric.fromMap(original.toMap());
        assertThat(reloaded).isEqualTo(original);
    }

    @Test void aReloadKeepsTheBandsItWasStoredWithRatherThanTodaysConstant() {
        Map<String, Object> stored = rubric().toMap();
        List<String> olderBands = List.of("旧0", "旧1", "旧2", "旧3", "旧4");
        stored.put("scoreBands", olderBands);
        assertThat(AdaptiveRubric.fromMap(stored).scoreBands()).containsExactlyElementsOf(olderBands);
    }

    @Test void aStoredRubricMissingItsBandsIsNotUsable() {
        Map<String, Object> stored = rubric().toMap();
        stored.remove("scoreBands");
        assertThatThrownBy(() -> AdaptiveRubric.fromMap(stored))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("五档");
    }

    @Test void aTargetRefusesARubricWrittenForAnotherQuestion() {
        var target = new QuestionTarget("数据库", "索引失效", 3, "TECHNICAL", QuestionOrigin.PLAN, null);
        assertThatThrownBy(() -> target.withRubric(AdaptiveRubric.create("索引失效", 4,
                List.of("要点"), List.of(), List.of())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("一致");
        assertThatThrownBy(() -> target.withRubric(AdaptiveRubric.create("锁冲突", 3,
                List.of("要点"), List.of(), List.of())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("一致");
    }

    @Test void aTargetCarriesItsRubricIntoTheBlueprintTheQuestionIsSavedWith() {
        var target = new QuestionTarget("数据库", "索引失效", 3, "TECHNICAL", QuestionOrigin.PLAN, null);
        QuestionBlueprint blueprint = target.withRubric(rubric());
        assertThat(blueprint.competency()).isEqualTo("数据库");
        assertThat(blueprint.difficulty()).isEqualTo(3);
        assertThat(blueprint.rubric()).containsEntry("knowledgePoint", "索引失效")
                .containsEntry("scoreBands", AdaptiveRubric.SCORE_BANDS);
    }
}