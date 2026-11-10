package com.projectardor.learning.practice.mockinterview.service.adaptive;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.projectardor.learning.practice.mockinterview.domain.AnswerKind;
import com.projectardor.learning.practice.mockinterview.domain.DifficultyFeedback;
import com.projectardor.learning.practice.mockinterview.domain.PolicyAction;
import com.projectardor.learning.practice.mockinterview.domain.QuestionOrigin;
import com.projectardor.learning.practice.mockinterview.service.adaptive.AdaptivePolicyEngine.Context;
import com.projectardor.learning.practice.mockinterview.service.adaptive.AdaptivePolicyEngine.Decision;
import com.projectardor.learning.practice.mockinterview.service.adaptive.InterviewPlanner.CompetencyTarget;

/**
 * Every row of the adaptive policy table, plus the constraints that keep it fair: one level at a
 * time, at most two consecutive follow-ups unless the candidate asked for a deep dive, an uncertain
 * reading re-asked instead of counted against them, and a reason recorded for every decision.
 */
class AdaptivePolicyEngineTests {
    private final AdaptivePolicyEngine engine = new AdaptivePolicyEngine();
    private final CompetencyTarget jvm = competency("JVM", 5, 3, "GC 选型", "内存模型");
    private final CompetencyTarget spring = competency("Spring", 3, 2, "Bean 生命周期", "事务传播");

    @Test void opensOnTheHighestPriorityCompetencyAtItsPlannedDifficulty() {
        Decision decision = engine.decide(context());
        assertThat(decision.action()).isEqualTo(PolicyAction.OPEN);
        assertThat(decision.target().competency()).isEqualTo("JVM");
        assertThat(decision.target().knowledgePoint()).isEqualTo("GC 选型");
        assertThat(decision.target().difficulty()).isEqualTo(3);
        assertThat(decision.target().origin()).isEqualTo(QuestionOrigin.PLAN);
        assertThat(decision.target().parentQuestionId()).isNull();
        assertThat(decision.reason()).contains("JVM").contains("3");
    }

    @Test void aPassRaisesOneLevelAndMovesToADeeperKnowledgePoint() {
        EvaluatedTurn last = answered("JVM", "GC 选型", 3, QuestionOrigin.PLAN, 4, "0.90");
        Decision decision = engine.decide(context(last));
        assertThat(decision.action()).isEqualTo(PolicyAction.RAISE);
        assertThat(decision.target().difficulty()).isEqualTo(4);
        assertThat(decision.target().knowledgePoint()).isEqualTo("内存模型");
        assertThat(decision.target().origin()).isEqualTo(QuestionOrigin.FOLLOW_UP);
        assertThat(decision.target().parentQuestionId()).isEqualTo(last.questionId());
    }

    @Test void aPartialAnswerHoldsDifficultyAndNamesWhatWasMissing() {
        EvaluatedTurn last = withMissing(answered("JVM", "GC 选型", 3, QuestionOrigin.PLAN, 2, "0.90"), "停顿预测");
        Decision decision = engine.decide(context(last));
        assertThat(decision.action()).isEqualTo(PolicyAction.HOLD);
        assertThat(decision.target().difficulty()).isEqualTo(3);
        assertThat(decision.target().knowledgePoint()).isEqualTo("GC 选型");
        assertThat(decision.reason()).contains("停顿预测");
    }

    @Test void aWrongAnswerLowersOneLevelToVerifyFundamentals() {
        Decision decision = engine.decide(context(answered("JVM", "GC 选型", 3, QuestionOrigin.PLAN, 0, "0.90")));
        assertThat(decision.action()).isEqualTo(PolicyAction.LOWER);
        assertThat(decision.target().difficulty()).isEqualTo(2);
        assertThat(decision.target().competency()).isEqualTo("JVM");
        assertThat(decision.reason()).contains("一次答错不代表没有能力");
    }

    @Test void oneWrongAnswerDoesNotAbandonTheCompetency() {
        Decision decision = engine.decide(context(answered("JVM", "GC 选型", 3, QuestionOrigin.PLAN, 1, "0.90")));
        assertThat(decision.action()).isEqualTo(PolicyAction.LOWER);
        assertThat(decision.target().competency()).isEqualTo("JVM");
    }

    @Test void repeatedInabilitySwitchesDomainInsteadOfDrillingTheSameWound() {
        Decision decision = engine.decide(context(
                answered("JVM", "GC 选型", 3, QuestionOrigin.PLAN, 0, "0.90"),
                answered("JVM", "GC 选型", 2, QuestionOrigin.FOLLOW_UP, 1, "0.90")));
        assertThat(decision.action()).isEqualTo(PolicyAction.SWITCH);
        assertThat(decision.target().competency()).isEqualTo("Spring");
        assertThat(decision.reason()).contains("边界");
    }

    @Test void aLowConfidenceReadingIsReAskedFromAnotherAngleWithoutDowngrading() {
        EvaluatedTurn last = answered("JVM", "GC 选型", 4, QuestionOrigin.PLAN, 0, "0.40");
        Decision decision = engine.decide(context(last));
        assertThat(decision.action()).isEqualTo(PolicyAction.CLARIFY);
        assertThat(decision.target().origin()).isEqualTo(QuestionOrigin.ALTERNATE);
        assertThat(decision.target().difficulty()).isEqualTo(4);
        assertThat(decision.target().parentQuestionId()).isEqualTo(last.questionId());
        assertThat(decision.reason()).contains("不据此下调能力");
    }

    @Test void anAlternateAngleIsOfferedOnlyOncePerKnowledgePoint() {
        Decision decision = engine.decide(context(
                answered("JVM", "GC 选型", 3, QuestionOrigin.PLAN, 0, "0.40"),
                answered("JVM", "GC 选型", 3, QuestionOrigin.ALTERNATE, 0, "0.40")));
        assertThat(decision.action()).isEqualTo(PolicyAction.SWITCH);
        assertThat(decision.reason()).contains("该维度保持未验证");
    }

    @Test void anOffTopicAnswerIsNotReadAsInability() {
        Decision decision = engine.decide(context(
                turn("JVM", "GC 选型", 3, QuestionOrigin.PLAN, AnswerKind.OFF_TOPIC, 0, "0.90",
                        List.of(), List.of(), null)));
        assertThat(decision.target().origin()).isEqualTo(QuestionOrigin.ALTERNATE);
        assertThat(decision.target().difficulty()).isEqualTo(3);
        assertThat(decision.reason()).contains("未针对题目");
    }

    @Test void aClarificationRequestIsReAskedWithoutScoring() {
        EvaluatedTurn last = turn("JVM", "GC 选型", 3, QuestionOrigin.PLAN, AnswerKind.CLARIFICATION, null, null,
                List.of(), List.of(), null);
        Decision decision = engine.decide(context(last));
        assertThat(decision.action()).isEqualTo(PolicyAction.CLARIFY);
        assertThat(decision.target().origin()).isEqualTo(QuestionOrigin.CLARIFICATION);
        assertThat(decision.target().knowledgePoint()).isEqualTo("GC 选型");
        assertThat(decision.target().difficulty()).isEqualTo(3);
        assertThat(decision.target().parentQuestionId()).isEqualTo(last.questionId());
        assertThat(decision.reason()).contains("不计分");
    }

    @Test void clarificationIsBoundedPerKnowledgePoint() {
        Decision decision = engine.decide(context(
                turn("JVM", "GC 选型", 3, QuestionOrigin.PLAN, AnswerKind.CLARIFICATION, null, null,
                        List.of(), List.of(), null),
                turn("JVM", "GC 选型", 3, QuestionOrigin.CLARIFICATION, AnswerKind.CLARIFICATION, null, null,
                        List.of(), List.of(), null)));
        assertThat(decision.action()).isEqualTo(PolicyAction.SWITCH);
        assertThat(decision.target().competency()).isEqualTo("Spring");
    }

    @Test void twoConsecutiveFollowUpsForceADomainSwitch() {
        Decision decision = engine.decide(context(
                answered("JVM", "GC 选型", 3, QuestionOrigin.PLAN, 3, "0.90"),
                answered("JVM", "内存模型", 4, QuestionOrigin.FOLLOW_UP, 3, "0.90"),
                answered("JVM", "GC 选型", 5, QuestionOrigin.FOLLOW_UP, 3, "0.90")));
        assertThat(decision.action()).isEqualTo(PolicyAction.SWITCH);
        assertThat(decision.target().competency()).isEqualTo("Spring");
        assertThat(decision.reason()).contains("连续追问");
    }

    @Test void aSwitchRestartsTheNewCompetencyOnItsOwnScale() {
        Decision decision = engine.decide(context(
                answered("JVM", "GC 选型", 3, QuestionOrigin.PLAN, 3, "0.90"),
                answered("JVM", "内存模型", 4, QuestionOrigin.FOLLOW_UP, 3, "0.90"),
                answered("JVM", "GC 选型", 5, QuestionOrigin.FOLLOW_UP, 3, "0.90")));
        assertThat(decision.target().difficulty()).isEqualTo(2);
        assertThat(decision.target().origin()).isEqualTo(QuestionOrigin.PLAN);
        assertThat(decision.target().parentQuestionId()).isNull();
    }

    @Test void deepDiveKeepsFollowingUpPastTheTwoQuestionLimit() {
        List<EvaluatedTurn> history = List.of(
                answered("JVM", "GC 选型", 3, QuestionOrigin.PLAN, 3, "0.90"),
                answered("JVM", "内存模型", 4, QuestionOrigin.FOLLOW_UP, 3, "0.90"),
                answered("JVM", "GC 选型", 5, QuestionOrigin.FOLLOW_UP, 2, "0.90"));
        Decision decision = engine.decide(new Context(List.of(jvm, spring), history, history.size(), true, null, null, null));
        assertThat(decision.action()).isEqualTo(PolicyAction.HOLD);
        assertThat(decision.target().competency()).isEqualTo("JVM");
    }

    @Test void evenADeepDiveStopsFollowingUpTheSameKnowledgePoint() {
        List<EvaluatedTurn> history = List.of(
                answered("JVM", "GC 选型", 3, QuestionOrigin.PLAN, 2, "0.90"),
                answered("JVM", "GC 选型", 3, QuestionOrigin.FOLLOW_UP, 2, "0.90"),
                answered("JVM", "GC 选型", 3, QuestionOrigin.FOLLOW_UP, 2, "0.90"));
        Decision decision = engine.decide(new Context(List.of(jvm, spring), history, history.size(), true, null, null, null));
        assertThat(decision.action()).isEqualTo(PolicyAction.SWITCH);
        assertThat(decision.target().competency()).isEqualTo("Spring");
        assertThat(decision.reason()).contains("不再追问");
    }

    @Test void rotatingKnowledgePointsCannotKeepADeepDivePastTheCompetencyCeiling() {
        List<EvaluatedTurn> history = new ArrayList<>();
        String[] points = {"GC 选型", "内存模型"};
        for (int index = 0; index < AdaptivePolicyEngine.MAX_TURNS_PER_COMPETENCY; index++) {
            history.add(answered("JVM", points[index % points.length], 3 + index % 3,
                    index == 0 ? QuestionOrigin.PLAN : QuestionOrigin.FOLLOW_UP, 4, "0.90"));
        }
        Decision decision = engine.decide(new Context(List.of(jvm, spring), history, history.size(), true, null, null, null));
        assertThat(decision.action()).isEqualTo(PolicyAction.SWITCH);
        assertThat(decision.target().competency()).isEqualTo("Spring");
        assertThat(decision.reason()).contains("单维度上限");
    }

    @Test void returningToACompetencySkipsTheKnowledgePointThatUsedUpItsBudget() {
        Decision decision = engine.decide(context(
                answered("Spring", "Bean 生命周期", 2, QuestionOrigin.PLAN, 3, "0.90"),
                answered("Spring", "Bean 生命周期", 2, QuestionOrigin.FOLLOW_UP, 2, "0.90"),
                answered("Spring", "Bean 生命周期", 2, QuestionOrigin.FOLLOW_UP, 2, "0.90"),
                answered("JVM", "GC 选型", 3, QuestionOrigin.PLAN, 3, "0.90"),
                answered("JVM", "内存模型", 4, QuestionOrigin.FOLLOW_UP, 3, "0.90"),
                answered("JVM", "GC 选型", 5, QuestionOrigin.FOLLOW_UP, 3, "0.90")));
        assertThat(decision.action()).isEqualTo(PolicyAction.SWITCH);
        assertThat(decision.target().competency()).isEqualTo("Spring");
        assertThat(decision.target().knowledgePoint()).isEqualTo("事务传播");
        assertThat(decision.target().origin()).isEqualTo(QuestionOrigin.PLAN);
    }

    @Test void aCompetencyWithNoEligiblePointLeftIsNotASwitchDestination() {
        CompetencyTarget redis = competency("Redis", 4, 2, "持久化");
        Decision decision = engine.decide(new Context(List.of(jvm, redis), List.of(
                answered("Redis", "持久化", 2, QuestionOrigin.PLAN, 3, "0.90"),
                answered("Redis", "持久化", 2, QuestionOrigin.FOLLOW_UP, 2, "0.90"),
                answered("Redis", "持久化", 2, QuestionOrigin.FOLLOW_UP, 2, "0.90"),
                answered("JVM", "GC 选型", 3, QuestionOrigin.PLAN, 3, "0.90"),
                answered("JVM", "内存模型", 4, QuestionOrigin.FOLLOW_UP, 3, "0.90"),
                answered("JVM", "GC 选型", 5, QuestionOrigin.FOLLOW_UP, 3, "0.90")),
                6, false, null, null, null));
        assertThat(decision.action()).isEqualTo(PolicyAction.TERMINATE);
        assertThat(decision.target()).isNull();
        assertThat(decision.reason()).contains("所有维度都已问到上限");
    }

    @Test void difficultyStaysOnTheScaleAndSaysSoAtTheCeiling() {
        Decision decision = engine.decide(context(answered("JVM", "内存模型", 5, QuestionOrigin.PLAN, 4, "0.90")));
        assertThat(decision.target().difficulty()).isEqualTo(5);
        assertThat(decision.action()).isEqualTo(PolicyAction.HOLD);
        assertThat(decision.reason()).contains("难度上限");
    }

    @Test void difficultyStaysOnTheScaleAndSaysSoAtTheFloor() {
        Decision decision = engine.decide(context(answered("JVM", "GC 选型", 1, QuestionOrigin.PLAN, 0, "0.90")));
        assertThat(decision.target().difficulty()).isEqualTo(1);
        assertThat(decision.action()).isEqualTo(PolicyAction.HOLD);
        assertThat(decision.reason()).contains("难度下限");
    }

    @Test void feedbackThatItWasTooHardOverridesAPassingScore() {
        EvaluatedTurn last = withFeedback(answered("JVM", "GC 选型", 3, QuestionOrigin.PLAN, 4, "0.90"),
                DifficultyFeedback.TOO_HARD);
        Decision decision = engine.decide(context(last));
        assertThat(decision.action()).isEqualTo(PolicyAction.LOWER);
        assertThat(decision.target().difficulty()).isEqualTo(2);
        assertThat(decision.reason()).contains("反馈偏难");
    }

    @Test void feedbackThatItWasTooEasyOverridesAPartialScore() {
        EvaluatedTurn last = withFeedback(answered("JVM", "GC 选型", 3, QuestionOrigin.PLAN, 2, "0.90"),
                DifficultyFeedback.TOO_EASY);
        Decision decision = engine.decide(context(last));
        assertThat(decision.action()).isEqualTo(PolicyAction.RAISE);
        assertThat(decision.target().difficulty()).isEqualTo(4);
        assertThat(decision.reason()).contains("反馈偏易");
    }

    @Test void noScoreOrFeedbackCombinationJumpsMoreThanOneLevelInsideACompetency() {
        for (int difficulty = 1; difficulty <= 5; difficulty++) {
            for (int score = 0; score <= 4; score++) {
                for (DifficultyFeedback feedback : DifficultyFeedback.values()) {
                    EvaluatedTurn last = withFeedback(
                            answered("JVM", "GC 选型", difficulty, QuestionOrigin.PLAN, score, "0.90"), feedback);
                    Decision decision = engine.decide(context(last));
                    assertThat(decision.target().origin()).isEqualTo(QuestionOrigin.FOLLOW_UP);
                    assertThat(decision.target().difficulty()).isBetween(1, 5);
                    assertThat(Math.abs(decision.target().difficulty() - difficulty)).isLessThanOrEqualTo(1);
                    assertThat(decision.reason()).isNotBlank();
                }
            }
        }
    }

    @Test void runningOutOfTimeTerminatesWithTheRemainingDomainsUnasked() {
        Instant now = Instant.parse("2026-11-09T10:00:00Z");
        Context timed = new Context(List.of(jvm, spring),
                List.of(answered("JVM", "GC 选型", 3, QuestionOrigin.PLAN, 3, "0.90")),
                1, false, 30, now.minusSeconds(31 * 60), now);
        Decision decision = engine.decide(timed);
        assertThat(decision.action()).isEqualTo(PolicyAction.TERMINATE);
        assertThat(decision.target()).isNull();
        assertThat(decision.reason()).contains("30");
    }

    @Test void timeIsNotCountedWhenTheCandidateChoseNoLimit() {
        Instant now = Instant.parse("2026-11-09T10:00:00Z");
        Context untimed = new Context(List.of(jvm, spring),
                List.of(answered("JVM", "GC 选型", 3, QuestionOrigin.PLAN, 3, "0.90")),
                1, false, null, now.minusSeconds(120 * 60), now);
        assertThat(engine.decide(untimed).action()).isEqualTo(PolicyAction.RAISE);
    }

    @Test void theQuestionCeilingTerminatesTheSession() {
        List<EvaluatedTurn> history = new ArrayList<>();
        for (int index = 0; index < AdaptivePolicyEngine.MAX_QUESTIONS; index++) {
            history.add(answered("JVM", "GC 选型", 3, QuestionOrigin.PLAN, 3, "0.90"));
        }
        Decision decision = engine.decide(new Context(List.of(jvm, spring), history, history.size(), false, null, null, null));
        assertThat(decision.action()).isEqualTo(PolicyAction.TERMINATE);
        assertThat(decision.reason()).contains("题目上限");
    }

    @Test void questionsThatCouldNotBeScoredStillConsumeTheQuestionBudget() {
        Decision decision = engine.decide(new Context(List.of(jvm, spring), List.of(),
                AdaptivePolicyEngine.MAX_QUESTIONS, false, null, null, null));
        assertThat(decision.action()).isEqualTo(PolicyAction.TERMINATE);
        assertThat(decision.reason()).contains("题目上限");
    }

    @Test void anInterviewWhoseAnswersAllFailedScoringStillEndsWhenItsTimeIsUp() {
        Instant now = Instant.parse("2026-11-09T10:00:00Z");
        Decision decision = engine.decide(new Context(List.of(jvm, spring), List.of(), 5,
                false, 30, now.minusSeconds(31 * 60), now));
        assertThat(decision.action()).isEqualTo(PolicyAction.TERMINATE);
        assertThat(decision.reason()).contains("30");
    }

    @Test void unscoredQuestionsBelowBothLimitsStillLeaveRoomToKeepAsking() {
        Decision decision = engine.decide(new Context(List.of(jvm, spring), List.of(), 2, false, null, null, null));
        assertThat(decision.action()).isEqualTo(PolicyAction.OPEN);
        assertThat(decision.target().competency()).isEqualTo("JVM");
    }

    @Test void aSessionEndsWhenEveryCompetencyReachedItsTurnCeiling() {
        List<EvaluatedTurn> history = new ArrayList<>();
        for (int index = 0; index < AdaptivePolicyEngine.MAX_TURNS_PER_COMPETENCY; index++) {
            history.add(answered("Spring", "Bean 生命周期", 2,
                    index == 0 ? QuestionOrigin.PLAN : QuestionOrigin.FOLLOW_UP, 3, "0.90"));
        }
        for (int index = 0; index < AdaptivePolicyEngine.MAX_TURNS_PER_COMPETENCY; index++) {
            history.add(answered("JVM", "GC 选型", 3,
                    index == 0 ? QuestionOrigin.PLAN : QuestionOrigin.FOLLOW_UP, 3, "0.90"));
        }
        Decision decision = engine.decide(new Context(List.of(jvm, spring), history, history.size(), false, null, null, null));
        assertThat(decision.action()).isEqualTo(PolicyAction.TERMINATE);
        assertThat(decision.reason()).contains("上限");
    }

    @Test void replayingTheSameHistoryProducesTheSameDecision() {
        Context replayed = context(
                answered("JVM", "GC 选型", 3, QuestionOrigin.PLAN, 2, "0.90"),
                answered("JVM", "GC 选型", 3, QuestionOrigin.FOLLOW_UP, 3, "0.90"));
        assertThat(engine.decide(replayed)).isEqualTo(engine.decide(replayed));
    }

    @Test void aTurnOutsideThePlanIsRejected() {
        assertThatThrownBy(() -> engine.decide(context(answered("Rust", "所有权", 3, QuestionOrigin.PLAN, 3, "0.90"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void anEmptyPlanIsRejected() {
        assertThatThrownBy(() -> new Context(List.of(), List.of(), 0, false, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Context context(EvaluatedTurn... history) {
        return new Context(List.of(jvm, spring), List.of(history), history.length, false, null, null, null);
    }

    private static CompetencyTarget competency(String skill, int priority, int difficulty, String... points) {
        return new CompetencyTarget(skill, priority, difficulty, "TECHNICAL", List.of(points), false, "JD 明确要求");
    }

    private static EvaluatedTurn answered(String skill, String point, int difficulty, QuestionOrigin origin,
            int score, String confidence) {
        return turn(skill, point, difficulty, origin, AnswerKind.ANSWER, score, confidence, List.of(), List.of(), null);
    }

    private static EvaluatedTurn turn(String skill, String point, int difficulty, QuestionOrigin origin,
            AnswerKind kind, Integer score, String confidence, List<String> missing, List<String> gaps,
            DifficultyFeedback feedback) {
        return new EvaluatedTurn(skill, point, difficulty, origin, kind, score,
                confidence == null ? null : new BigDecimal(confidence), List.of(), missing, gaps, feedback,
                UUID.randomUUID());
    }

    private static EvaluatedTurn withFeedback(EvaluatedTurn source, DifficultyFeedback feedback) {
        return new EvaluatedTurn(source.competency(), source.knowledgePoint(), source.difficulty(), source.origin(),
                source.kind(), source.score(), source.confidence(), source.coveredPoints(), source.missingPoints(),
                source.knowledgeGaps(), feedback, source.questionId());
    }

    private static EvaluatedTurn withMissing(EvaluatedTurn source, String... missing) {
        return new EvaluatedTurn(source.competency(), source.knowledgePoint(), source.difficulty(), source.origin(),
                source.kind(), source.score(), source.confidence(), source.coveredPoints(), List.of(missing),
                source.knowledgeGaps(), source.feedback(), source.questionId());
    }
}