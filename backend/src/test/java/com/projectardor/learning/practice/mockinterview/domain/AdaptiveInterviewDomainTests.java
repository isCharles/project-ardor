package com.projectardor.learning.practice.mockinterview.domain;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class AdaptiveInterviewDomainTests {
    private final UUID owner = UUID.randomUUID();
    private final UUID session = UUID.randomUUID();

    private QuestionBlueprint blueprint(int difficulty) {
        return new QuestionBlueprint("JVM", "垃圾回收器选型", difficulty,
                Map.of("mustHavePoints", List.of("分代假设", "停顿与吞吐权衡")),
                QuestionOrigin.PLAN, null);
    }

    @Test void classicFactoriesLeaveEveryAdaptiveColumnEmpty() {
        var classicSession = InterviewSession.create(owner, null, InterviewModality.TEXT, null, "Backend", null);
        assertThat(classicSession.getMode()).isEqualTo(InterviewMode.CLASSIC);
        assertThat(classicSession.getSeniority()).isNull();
        assertThat(classicSession.getDurationMinutes()).isNull();
        assertThat(classicSession.isDeepDive()).isFalse();
        assertThat(classicSession.getJobDescription()).isNull();

        var classicQuestion = InterviewQuestion.create(owner, session, 1, "Question", "TECHNICAL", List.of("Correctness"));
        assertThat(classicQuestion.getCompetency()).isNull();
        assertThat(classicQuestion.getKnowledgePoint()).isNull();
        assertThat(classicQuestion.getDifficulty()).isNull();
        assertThat(classicQuestion.getRubric()).isNull();
        assertThat(classicQuestion.getOrigin()).isNull();
        assertThat(classicQuestion.getParentQuestionId()).isNull();
        assertThat(classicQuestion.getDifficultyFeedback()).isNull();
    }

    @Test void anAdaptiveSessionCarriesItsModeAndBudget() {
        var adaptive = InterviewSession.createAdaptive(owner, null, "Acme", "Backend", null,
                "SENIOR", 30, true, "JD text");
        assertThat(adaptive.getMode()).isEqualTo(InterviewMode.ADAPTIVE);
        assertThat(adaptive.getModality()).isEqualTo(InterviewModality.TEXT);
        assertThat(adaptive.getStatus()).isEqualTo(InterviewStatus.IN_PROGRESS);
        assertThat(adaptive.getSeniority()).isEqualTo("SENIOR");
        assertThat(adaptive.getDurationMinutes()).isEqualTo(30);
        assertThat(adaptive.isDeepDive()).isTrue();
        assertThat(adaptive.getJobDescription()).isEqualTo("JD text");
        assertThatThrownBy(() -> InterviewSession.createAdaptive(owner, null, null, "Backend", null,
                null, 0, false, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void aBlueprintRefusesADifficultyOutsideTheOneToFiveScale() {
        assertThatThrownBy(() -> blueprint(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> blueprint(6)).isInstanceOf(IllegalArgumentException.class);
        assertThat(blueprint(1).difficulty()).isEqualTo(1);
        assertThat(blueprint(5).difficulty()).isEqualTo(5);
    }

    @Test void aBlueprintRefusesToExistWithoutARubricOrATarget() {
        assertThatThrownBy(() -> new QuestionBlueprint("JVM", "GC", 3, Map.of(), QuestionOrigin.PLAN, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuestionBlueprint("JVM", "GC", 3, null, QuestionOrigin.PLAN, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuestionBlueprint(" ", "GC", 3, Map.of("k", "v"), QuestionOrigin.PLAN, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuestionBlueprint("JVM", " ", 3, Map.of("k", "v"), QuestionOrigin.PLAN, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuestionBlueprint("JVM", "GC", 3, Map.of("k", "v"), null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void editingTheCallersRubricCannotRewriteWhatTheQuestionWasAskedWith() {
        Map<String, Object> rubric = new LinkedHashMap<>();
        rubric.put("mustHavePoints", List.of("分代假设"));
        var blueprint = new QuestionBlueprint("JVM", "GC", 3, rubric, QuestionOrigin.PLAN, null);
        rubric.put("mustHavePoints", List.of("something else"));
        rubric.put("lateAddition", "x");
        assertThat(blueprint.rubric()).containsOnlyKeys("mustHavePoints");
        assertThat(blueprint.rubric().get("mustHavePoints")).isEqualTo(List.of("分代假设"));
        assertThatThrownBy(() -> blueprint.rubric().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void anAdaptiveQuestionKeepsItsBlueprintAndFollowUpParent() {
        UUID parent = UUID.randomUUID();
        var followUp = new QuestionBlueprint("JVM", "G1 停顿预测", 4, Map.of("mustHavePoints", List.of("Region")),
                QuestionOrigin.FOLLOW_UP, parent);
        var question = InterviewQuestion.createAdaptive(owner, session, 2, "Why G1?", "TECHNICAL",
                List.of("Region"), followUp, null);
        assertThat(question.getCompetency()).isEqualTo("JVM");
        assertThat(question.getKnowledgePoint()).isEqualTo("G1 停顿预测");
        assertThat(question.getDifficulty()).isEqualTo(4);
        assertThat(question.getOrigin()).isEqualTo(QuestionOrigin.FOLLOW_UP);
        assertThat(question.getParentQuestionId()).isEqualTo(parent);
        assertThat(question.getRubric()).isEqualTo(Map.of("mustHavePoints", List.of("Region")));
        assertThat(question.getEvaluationCriteria()).containsExactly("Region");
        assertThatThrownBy(() -> InterviewQuestion.createAdaptive(owner, session, 1, "Q", "TECHNICAL",
                List.of(), null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void onlyAnAdaptiveQuestionCanRecordDifficultyFeedback() {
        var classic = InterviewQuestion.create(owner, session, 1, "Q", "TECHNICAL", List.of());
        assertThatThrownBy(() -> classic.recordDifficultyFeedback(DifficultyFeedback.TOO_HARD))
                .isInstanceOf(IllegalStateException.class);
        var adaptive = InterviewQuestion.createAdaptive(owner, session, 1, "Q", "TECHNICAL", List.of(),
                blueprint(3), null);
        adaptive.recordDifficultyFeedback(DifficultyFeedback.TOO_HARD);
        assertThat(adaptive.getDifficultyFeedback()).isEqualTo(DifficultyFeedback.TOO_HARD);
    }

    @Test void aScoredTurnStaysInsideTheZeroToFourRubric() {
        for (int score : new int[] {-1, 5}) {
            assertThatThrownBy(() -> InterviewTurnEvaluation.createScored(owner, session, UUID.randomUUID(),
                    UUID.randomUUID(), AnswerKind.ANSWER, score, new BigDecimal("0.80"), Map.of(), "m", "v1"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        var turn = InterviewTurnEvaluation.createScored(owner, session, UUID.randomUUID(), UUID.randomUUID(),
                AnswerKind.UNKNOWN, 0, new BigDecimal("0.90"), Map.of("knowledgeGaps", List.of("GC")), "m", "v1");
        assertThat(turn.getScore()).isZero();
        assertThat(turn.getAnswerKind()).isEqualTo(AnswerKind.UNKNOWN);
        assertThat(turn.getConfidence()).isEqualByComparingTo("0.90");
    }

    @Test void aScoredTurnRefusesConfidenceOutsideZeroToOne() {
        for (String confidence : new String[] {"-0.1", "1.1"}) {
            assertThatThrownBy(() -> InterviewTurnEvaluation.createScored(owner, session, UUID.randomUUID(),
                    UUID.randomUUID(), AnswerKind.ANSWER, 3, new BigDecimal(confidence), Map.of(), "m", "v1"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> InterviewTurnEvaluation.createScored(owner, session, UUID.randomUUID(),
                UUID.randomUUID(), AnswerKind.ANSWER, 3, null, Map.of(), "m", "v1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void aClarificationRequestIsNeverScored() {
        var clarification = InterviewTurnEvaluation.createClarification(owner, session, UUID.randomUUID(),
                UUID.randomUUID(), Map.of("clarification", "哪个版本？"), "m", "v1");
        assertThat(clarification.getAnswerKind()).isEqualTo(AnswerKind.CLARIFICATION);
        assertThat(clarification.getScore()).isNull();
        assertThat(clarification.getConfidence()).isNull();
        assertThatThrownBy(() -> InterviewTurnEvaluation.createScored(owner, session, UUID.randomUUID(),
                UUID.randomUUID(), AnswerKind.CLARIFICATION, 2, BigDecimal.ONE, Map.of(), "m", "v1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void anUntestedCompetencyIsNeverGivenALevel() {
        var assessment = InterviewCompetencyAssessment.create(owner, session, "JVM", 5);
        assertThat(assessment.getState()).isEqualTo(CompetencyState.UNVERIFIED);
        assertThat(assessment.getEstimatedLevel()).isNull();
        assertThat(assessment.getEvidenceCount()).isZero();
        assertThat(assessment.getConfidence()).isEqualByComparingTo("0");
        assertThatThrownBy(() -> assessment.recordEvidence(3, new BigDecimal("0.5"), 0,
                CompetencyState.UNVERIFIED, List.of(), List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> assessment.recordEvidence(null, new BigDecimal("0.5"), 2,
                CompetencyState.VERIFIED, List.of(), List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void aVerifiedCompetencyRecordsItsEvidenceAndGaps() {
        var assessment = InterviewCompetencyAssessment.create(owner, session, "JVM", 4);
        List<Map<String, Object>> gaps = new ArrayList<>();
        gaps.add(new LinkedHashMap<>(Map.of("point", "停顿预测", "severity", "HIGH")));
        assessment.recordEvidence(3, new BigDecimal("0.75"), 2, CompetencyState.GAP_EXPOSED,
                List.of(Map.of("point", "分代假设")), gaps);
        gaps.clear();
        assertThat(assessment.getState()).isEqualTo(CompetencyState.GAP_EXPOSED);
        assertThat(assessment.getEstimatedLevel()).isEqualTo(3);
        assertThat(assessment.getEvidenceCount()).isEqualTo(2);
        assertThat(assessment.getJdPriority()).isEqualTo(4);
        assertThat(assessment.getStrengths()).containsExactly(Map.of("point", "分代假设"));
        assertThat(assessment.getKnowledgeGaps()).hasSize(1);
        assertThatThrownBy(() -> assessment.getKnowledgeGaps().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> InterviewCompetencyAssessment.create(owner, session, "JVM", 6))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> assessment.recordEvidence(6, BigDecimal.ONE, 1,
                CompetencyState.VERIFIED, List.of(), List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void aFailedScoringCallLeavesTheAnswerRecoverable() {
        var answer = InterviewAnswer.create(owner, session, UUID.randomUUID(), "An answer", 12);
        assertThat(answer.getEvaluationAttempts()).isZero();
        assertThat(answer.getEvaluationError()).isNull();
        answer.recordEvaluationFailure("LLM_INVALID_RESPONSE");
        answer.recordEvaluationFailure("LLM_NETWORK_FAILED");
        assertThat(answer.getEvaluationAttempts()).isEqualTo(2);
        assertThat(answer.getEvaluationError()).isEqualTo("LLM_NETWORK_FAILED");
        assertThat(answer.getAnswerText()).isEqualTo("An answer");
        answer.clearEvaluationFailure();
        assertThat(answer.getEvaluationError()).isNull();
        assertThat(answer.getEvaluationAttempts()).isEqualTo(2);
    }

    @Test void aPolicyDecisionAlwaysCarriesItsReason() {
        assertThatThrownBy(() -> InterviewPolicyDecision.create(owner, session, 1, null,
                PolicyAction.OPEN, " ", Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InterviewPolicyDecision.create(owner, session, 0, null,
                PolicyAction.OPEN, "开场", Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InterviewPolicyDecision.create(owner, session, 1, null,
                null, "开场", Map.of())).isInstanceOf(IllegalArgumentException.class);
        var decision = InterviewPolicyDecision.create(owner, session, 1, null,
                PolicyAction.OPEN, "开场", Map.of("competency", "JVM"));
        assertThat(decision.getAction()).isEqualTo(PolicyAction.OPEN);
        assertThat(decision.getReason()).isEqualTo("开场");
        assertThat(decision.getDecision()).isEqualTo(Map.of("competency", "JVM"));
    }

    @Test void anAdaptiveReportKeepsItsOwnPromptVersion() {
        var classic = InterviewEvaluation.create(owner, session, new BigDecimal("80.00"), Map.of(), "m");
        assertThat(classic.getPromptVersion()).isEqualTo("interview-evaluation-v1");
        var adaptive = InterviewEvaluation.create(owner, session, new BigDecimal("80.00"), Map.of(), "m",
                "adaptive-interview-report-v1");
        assertThat(adaptive.getPromptVersion()).isEqualTo("adaptive-interview-report-v1");
    }
}