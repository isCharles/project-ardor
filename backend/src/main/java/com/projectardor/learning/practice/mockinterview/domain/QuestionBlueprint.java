package com.projectardor.learning.practice.mockinterview.domain;

import java.util.Map;
import java.util.UUID;

/** Decided before the model writes the question, so scoring later reads this rubric instead of inventing one. */
public record QuestionBlueprint(String competency, String knowledgePoint, int difficulty,
        Map<String, Object> rubric, QuestionOrigin origin, UUID parentQuestionId) {

    public QuestionBlueprint {
        if (competency == null || competency.isBlank()) throw new IllegalArgumentException("能力维度不能为空");
        if (knowledgePoint == null || knowledgePoint.isBlank()) throw new IllegalArgumentException("知识点不能为空");
        if (difficulty < 1 || difficulty > 5) throw new IllegalArgumentException("难度必须在 1 到 5 之间");
        if (rubric == null || rubric.isEmpty()) throw new IllegalArgumentException("题目评分标准不能为空");
        if (origin == null) throw new IllegalArgumentException("题目来源不能为空");
        competency = competency.strip();
        knowledgePoint = knowledgePoint.strip();
        rubric = Map.copyOf(rubric);
    }
}