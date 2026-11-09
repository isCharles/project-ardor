package com.projectardor.learning.practice.mockinterview.service.adaptive;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.projectardor.learning.practice.mockinterview.domain.AnswerKind;
import com.projectardor.learning.practice.mockinterview.domain.DifficultyFeedback;
import com.projectardor.learning.practice.mockinterview.domain.QuestionOrigin;

/**
 * One evaluated turn as the deterministic layer sees it. No entities, no repositories and no model
 * call, so the policy and the profile can be replayed from persisted rows and tested as a table.
 * A clarification carries a null score because nothing was assessed.
 */
public record EvaluatedTurn(String competency, String knowledgePoint, int difficulty, QuestionOrigin origin,
        AnswerKind kind, Integer score, BigDecimal confidence, List<String> coveredPoints,
        List<String> missingPoints, List<String> knowledgeGaps, DifficultyFeedback feedback, UUID questionId) {

    public EvaluatedTurn {
        if (competency == null || competency.isBlank()) throw new IllegalArgumentException("能力维度不能为空");
        if (knowledgePoint == null || knowledgePoint.isBlank()) throw new IllegalArgumentException("知识点不能为空");
        if (difficulty < 1 || difficulty > 5) throw new IllegalArgumentException("难度必须在 1 到 5 之间");
        if (kind == null) throw new IllegalArgumentException("回答类型不能为空");
        if (kind == AnswerKind.CLARIFICATION && score != null) {
            throw new IllegalArgumentException("反问澄清不计分");
        }
        if (kind != AnswerKind.CLARIFICATION && (score == null || score < 0 || score > 4)) {
            throw new IllegalArgumentException("已评分的回答必须给出 0 到 4 的分数");
        }
        coveredPoints = coveredPoints == null ? List.of() : List.copyOf(coveredPoints);
        missingPoints = missingPoints == null ? List.of() : List.copyOf(missingPoints);
        knowledgeGaps = knowledgeGaps == null ? List.of() : List.copyOf(knowledgeGaps);
    }

    public boolean isPass() {
        return score != null && score >= 3;
    }

    public boolean isInability() {
        return score != null && score <= 1 && kind != AnswerKind.OFF_TOPIC;
    }
}