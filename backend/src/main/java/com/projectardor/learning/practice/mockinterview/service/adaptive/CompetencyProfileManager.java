package com.projectardor.learning.practice.mockinterview.service.adaptive;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.projectardor.learning.practice.mockinterview.domain.AnswerKind;
import com.projectardor.learning.practice.mockinterview.domain.CompetencyState;

/**
 * Turns evaluated answers into a competency profile. The level is the hardest difficulty the
 * candidate actually passed, so one later failure cannot erase an earlier pass, and confidence
 * grows with the spread of difficulties that produced evidence rather than with a sum of scores.
 *
 * <p>A competency nobody could assess keeps a null level and stays UNVERIFIED: untested is never
 * reported as "cannot do". The whole profile is folded from the turn list, so replaying persisted
 * turns after an interruption reproduces it exactly.
 */
@Component
public class CompetencyProfileManager {

    private static final BigDecimal ONE_DIFFICULTY = new BigDecimal("0.60");
    private static final BigDecimal TWO_DIFFICULTIES = new BigDecimal("0.80");
    private static final BigDecimal THREE_DIFFICULTIES = new BigDecimal("0.95");

    public record Snapshot(String skill, int jdPriority, Integer estimatedLevel, BigDecimal confidence,
            int evidenceCount, CompetencyState state, List<Map<String, Object>> strengths,
            List<Map<String, Object>> knowledgeGaps) {
        public Snapshot {
            strengths = List.copyOf(strengths);
            knowledgeGaps = List.copyOf(knowledgeGaps);
        }
    }

    public Snapshot opening(String skill, int jdPriority) {
        return new Snapshot(skill, jdPriority, null, BigDecimal.ZERO, 0, CompetencyState.UNVERIFIED,
                List.of(), List.of());
    }

    public Snapshot summarize(String skill, int jdPriority, List<EvaluatedTurn> turns) {
        Integer level = null;
        boolean boundary = false;
        int evidenceCount = 0;
        BigDecimal strongestReading = BigDecimal.ZERO;
        Set<Integer> evidenceDifficulties = new LinkedHashSet<>();
        Set<Map<String, Object>> strengths = new LinkedHashSet<>();
        Set<Map<String, Object>> gaps = new LinkedHashSet<>();

        for (EvaluatedTurn turn : turns) {
            if (!turn.competency().equals(skill) || !isEvidence(turn)) continue;
            evidenceCount++;
            evidenceDifficulties.add(turn.difficulty());
            strongestReading = strongestReading.max(turn.confidence());
            if (turn.isPass()) {
                level = level == null ? turn.difficulty() : Math.max(level, turn.difficulty());
                strengths.add(entry(turn.knowledgePoint(), turn.difficulty(), "score", turn.score()));
            } else {
                if (turn.score() <= 1 || turn.kind() == AnswerKind.UNKNOWN) boundary = true;
                for (String detail : gapDetails(turn)) {
                    gaps.add(entry(turn.knowledgePoint(), turn.difficulty(), "detail", detail));
                }
            }
        }

        CompetencyState state = level != null ? CompetencyState.VERIFIED
                : boundary ? CompetencyState.GAP_EXPOSED : CompetencyState.UNVERIFIED;
        BigDecimal confidence = state == CompetencyState.UNVERIFIED
                ? BigDecimal.ZERO
                : spread(evidenceDifficulties.size()).min(strongestReading);
        return new Snapshot(skill, jdPriority, level, confidence, evidenceCount, state,
                List.copyOf(strengths), List.copyOf(gaps));
    }

    /** Only a confident reading of an on-topic answer says anything about ability. */
    private static boolean isEvidence(EvaluatedTurn turn) {
        return turn.kind() != AnswerKind.CLARIFICATION
                && turn.kind() != AnswerKind.OFF_TOPIC
                && turn.confidence() != null
                && !AdaptivePolicyEngine.isUncertain(turn);
    }

    private static List<String> gapDetails(EvaluatedTurn turn) {
        // A partial answer names both what the model judged missing and what the rubric still
        // expects; keeping only one of them would drop evidence the report needs to cite.
        LinkedHashSet<String> details = new LinkedHashSet<>(turn.knowledgeGaps());
        if (turn.score() == 2) details.addAll(turn.missingPoints());
        if (details.isEmpty()) details.add(turn.knowledgePoint());
        return List.copyOf(details);
    }

    private static BigDecimal spread(int difficulties) {
        if (difficulties >= 3) return THREE_DIFFICULTIES;
        if (difficulties == 2) return TWO_DIFFICULTIES;
        return ONE_DIFFICULTY;
    }

    private static Map<String, Object> entry(String knowledgePoint, int difficulty, String key, Object value) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("knowledgePoint", knowledgePoint);
        entry.put("difficulty", difficulty);
        entry.put(key, value);
        return entry;
    }
}