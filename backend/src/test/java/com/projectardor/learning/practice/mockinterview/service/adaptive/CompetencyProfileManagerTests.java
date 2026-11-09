package com.projectardor.learning.practice.mockinterview.service.adaptive;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.projectardor.learning.practice.mockinterview.domain.AnswerKind;
import com.projectardor.learning.practice.mockinterview.domain.CompetencyState;
import com.projectardor.learning.practice.mockinterview.domain.InterviewCompetencyAssessment;
import com.projectardor.learning.practice.mockinterview.domain.QuestionOrigin;
import com.projectardor.learning.practice.mockinterview.service.adaptive.CompetencyProfileManager.Snapshot;

/**
 * The profile is evidence, not a score average: the level is the hardest difficulty actually
 * passed, confidence comes from the spread of difficulties rather than a sum, and a competency
 * nobody could assess keeps a null level instead of being reported as "cannot do".
 */
class CompetencyProfileManagerTests {
    private final CompetencyProfileManager profiles = new CompetencyProfileManager();

    @Test void anUntestedCompetencyHasNoLevelAndNoConfidence() {
        Snapshot opening = profiles.opening("JVM", 5);
        assertThat(opening.state()).isEqualTo(CompetencyState.UNVERIFIED);
        assertThat(opening.estimatedLevel()).isNull();
        assertThat(opening.confidence()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(opening.evidenceCount()).isZero();
        assertThat(opening.strengths()).isEmpty();
        assertThat(opening.knowledgeGaps()).isEmpty();
        assertThat(profiles.summarize("JVM", 5, List.of())).isEqualTo(opening);
    }

    @Test void aPassVerifiesTheLevelAtTheDifficultyActuallyPassed() {
        Snapshot profile = profiles.summarize("JVM", 5, List.of(passed("GC 选型", 3, "0.90")));
        assertThat(profile.state()).isEqualTo(CompetencyState.VERIFIED);
        assertThat(profile.estimatedLevel()).isEqualTo(3);
        assertThat(profile.evidenceCount()).isEqualTo(1);
        assertThat(profile.confidence()).isEqualByComparingTo("0.60");
        assertThat(profile.strengths()).containsExactly(
                Map.of("knowledgePoint", "GC 选型", "difficulty", 3, "score", 3));
    }

    @Test void confidenceComesFromTheSpreadOfDifficultiesNotTheNumberOfScores() {
        Snapshot repeated = profiles.summarize("JVM", 5, List.of(
                passed("GC 选型", 3, "0.90"), passed("GC 选型", 3, "0.90"), passed("内存模型", 3, "0.90")));
        assertThat(repeated.evidenceCount()).isEqualTo(3);
        assertThat(repeated.confidence()).isEqualByComparingTo("0.60");

        Snapshot spread = profiles.summarize("JVM", 5, List.of(
                passed("GC 选型", 2, "0.99"), passed("GC 选型", 3, "0.99"), passed("内存模型", 4, "0.99")));
        assertThat(spread.estimatedLevel()).isEqualTo(4);
        assertThat(spread.confidence()).isEqualByComparingTo("0.95");
    }

    @Test void twoDifficultiesOfEvidenceGiveMediumConfidence() {
        Snapshot profile = profiles.summarize("JVM", 5, List.of(
                passed("GC 选型", 2, "0.90"), passed("内存模型", 3, "0.90")));
        assertThat(profile.confidence()).isEqualByComparingTo("0.80");
    }

    @Test void profileConfidenceNeverExceedsTheReadingItIsBasedOn() {
        Snapshot profile = profiles.summarize("JVM", 5, List.of(
                passed("GC 选型", 2, "0.70"), passed("GC 选型", 3, "0.70"), passed("内存模型", 4, "0.70")));
        assertThat(profile.confidence()).isEqualByComparingTo("0.70");
    }

    @Test void oneLaterFailureCannotEraseAnEarlierPass() {
        Snapshot profile = profiles.summarize("JVM", 5, List.of(
                passed("GC 选型", 4, "0.90"), failed("内存模型", 5, 0, "0.90", List.of("volatile 可见性"))));
        assertThat(profile.state()).isEqualTo(CompetencyState.VERIFIED);
        assertThat(profile.estimatedLevel()).isEqualTo(4);
        assertThat(profile.knowledgeGaps()).containsExactly(
                Map.of("knowledgePoint", "内存模型", "difficulty", 5, "detail", "volatile 可见性"));
    }

    @Test void aFailureAloneExposesAGapWithoutInventingALevel() {
        Snapshot profile = profiles.summarize("JVM", 5, List.of(failed("GC 选型", 3, 1, "0.90", List.of("分代假设"))));
        assertThat(profile.state()).isEqualTo(CompetencyState.GAP_EXPOSED);
        assertThat(profile.estimatedLevel()).isNull();
        assertThat(profile.confidence()).isEqualByComparingTo("0.60");
        assertThat(profile.knowledgeGaps()).hasSize(1);
    }

    @Test void anExplicitIDontKnowRecordsTheKnowledgePointItselfAsTheBoundary() {
        EvaluatedTurn unknown = new EvaluatedTurn("JVM", "GC 选型", 3, QuestionOrigin.PLAN, AnswerKind.UNKNOWN, 0,
                new BigDecimal("0.90"), List.of(), List.of(), List.of(), null, UUID.randomUUID());
        Snapshot profile = profiles.summarize("JVM", 5, List.of(unknown));
        assertThat(profile.state()).isEqualTo(CompetencyState.GAP_EXPOSED);
        assertThat(profile.knowledgeGaps()).containsExactly(
                Map.of("knowledgePoint", "GC 选型", "difficulty", 3, "detail", "GC 选型"));
    }

    @Test void aLowConfidenceReadingChangesNothingAtAll() {
        Snapshot profile = profiles.summarize("JVM", 5, List.of(failed("GC 选型", 3, 0, "0.40", List.of("分代假设"))));
        assertThat(profile).isEqualTo(profiles.opening("JVM", 5));
    }

    @Test void aLowConfidenceFailureDoesNotLowerAnAlreadyVerifiedLevel() {
        Snapshot profile = profiles.summarize("JVM", 5, List.of(
                passed("GC 选型", 4, "0.90"), failed("GC 选型", 5, 0, "0.40", List.of("G1 停顿预测"))));
        assertThat(profile.estimatedLevel()).isEqualTo(4);
        assertThat(profile.state()).isEqualTo(CompetencyState.VERIFIED);
        assertThat(profile.evidenceCount()).isEqualTo(1);
        assertThat(profile.knowledgeGaps()).isEmpty();
    }

    @Test void aClarificationRequestIsNotEvidence() {
        EvaluatedTurn clarification = new EvaluatedTurn("JVM", "GC 选型", 3, QuestionOrigin.PLAN,
                AnswerKind.CLARIFICATION, null, null, List.of(), List.of(), List.of(), null, UUID.randomUUID());
        assertThat(profiles.summarize("JVM", 5, List.of(clarification))).isEqualTo(profiles.opening("JVM", 5));
    }

    @Test void anOffTopicAnswerSaysNothingAboutAbility() {
        EvaluatedTurn offTopic = new EvaluatedTurn("JVM", "GC 选型", 3, QuestionOrigin.PLAN, AnswerKind.OFF_TOPIC, 0,
                new BigDecimal("0.90"), List.of(), List.of(), List.of("聊了项目排期"), null, UUID.randomUUID());
        assertThat(profiles.summarize("JVM", 5, List.of(offTopic))).isEqualTo(profiles.opening("JVM", 5));
    }

    @Test void aPartialAnswerRecordsMissingPointsWithoutDeclaringInability() {
        EvaluatedTurn partial = new EvaluatedTurn("JVM", "GC 选型", 3, QuestionOrigin.PLAN, AnswerKind.ANSWER, 2,
                new BigDecimal("0.90"), List.of("分代假设"), List.of("停顿预测"), List.of(), null, UUID.randomUUID());
        Snapshot profile = profiles.summarize("JVM", 5, List.of(partial));
        assertThat(profile.state()).isEqualTo(CompetencyState.UNVERIFIED);
        assertThat(profile.estimatedLevel()).isNull();
        assertThat(profile.evidenceCount()).isEqualTo(1);
        assertThat(profile.knowledgeGaps()).containsExactly(
                Map.of("knowledgePoint", "GC 选型", "difficulty", 3, "detail", "停顿预测"));
    }

    @Test void turnsFromOtherCompetenciesAreIgnored() {
        Snapshot profile = profiles.summarize("JVM", 5, List.of(
                passed("GC 选型", 3, "0.90"),
                new EvaluatedTurn("Spring", "事务传播", 4, QuestionOrigin.PLAN, AnswerKind.ANSWER, 4,
                        new BigDecimal("0.90"), List.of(), List.of(), List.of(), null, UUID.randomUUID())));
        assertThat(profile.evidenceCount()).isEqualTo(1);
        assertThat(profile.estimatedLevel()).isEqualTo(3);
    }

    @Test void repeatedIdenticalEvidenceIsRecordedOnceButStillCounted() {
        Snapshot profile = profiles.summarize("JVM", 5, List.of(passed("GC 选型", 3, "0.90"), passed("GC 选型", 3, "0.90")));
        assertThat(profile.strengths()).hasSize(1);
        assertThat(profile.evidenceCount()).isEqualTo(2);
    }

    @Test void replayingTheSameTurnsReproducesTheSameProfile() {
        List<EvaluatedTurn> turns = List.of(passed("GC 选型", 3, "0.90"), failed("内存模型", 4, 1, "0.90", List.of("屏障")));
        assertThat(profiles.summarize("JVM", 5, turns)).isEqualTo(profiles.summarize("JVM", 5, turns));
    }

    @Test void theProfileCanBePersistedWithoutBreakingTheEntityInvariants() {
        UUID owner = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        for (List<EvaluatedTurn> turns : List.of(List.<EvaluatedTurn>of(), List.of(passed("GC 选型", 3, "0.90")),
                List.of(failed("GC 选型", 3, 0, "0.90", List.of("分代假设"))),
                List.of(failed("GC 选型", 3, 0, "0.40", List.of("分代假设"))))) {
            Snapshot profile = profiles.summarize("JVM", 5, turns);
            InterviewCompetencyAssessment assessment = InterviewCompetencyAssessment.create(owner, session, "JVM", 5);
            assessment.recordEvidence(profile.estimatedLevel(), profile.confidence(), profile.evidenceCount(),
                    profile.state(), profile.strengths(), profile.knowledgeGaps());
            assertThat(assessment.getEstimatedLevel()).isEqualTo(profile.estimatedLevel());
            assertThat(assessment.getState()).isEqualTo(profile.state());
            assertThat(assessment.getKnowledgeGaps()).isEqualTo(profile.knowledgeGaps());
        }
    }

    private static EvaluatedTurn passed(String point, int difficulty, String confidence) {
        return new EvaluatedTurn("JVM", point, difficulty, QuestionOrigin.PLAN, AnswerKind.ANSWER, 3,
                new BigDecimal(confidence), List.of(point), List.of(), List.of(), null, UUID.randomUUID());
    }

    private static EvaluatedTurn failed(String point, int difficulty, int score, String confidence, List<String> gaps) {
        return new EvaluatedTurn("JVM", point, difficulty, QuestionOrigin.FOLLOW_UP, AnswerKind.ANSWER, score,
                new BigDecimal(confidence), List.of(), List.of(), gaps, null, UUID.randomUUID());
    }
}