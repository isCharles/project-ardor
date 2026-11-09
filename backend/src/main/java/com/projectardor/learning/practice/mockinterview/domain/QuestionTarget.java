package com.projectardor.learning.practice.mockinterview.domain;

import java.util.UUID;

/** What the policy decided to ask next. The rubric is filled in before the question reaches the candidate. */
public record QuestionTarget(String competency, String knowledgePoint, int difficulty, String questionType,
        QuestionOrigin origin, UUID parentQuestionId) {

    public QuestionTarget {
        if (competency == null || competency.isBlank()) throw new IllegalArgumentException("能力维度不能为空");
        if (knowledgePoint == null || knowledgePoint.isBlank()) throw new IllegalArgumentException("知识点不能为空");
        if (difficulty < 1 || difficulty > 5) throw new IllegalArgumentException("难度必须在 1 到 5 之间");
        if (origin == null) throw new IllegalArgumentException("题目来源不能为空");
        competency = competency.strip();
        knowledgePoint = knowledgePoint.strip();
        questionType = questionType == null || questionType.isBlank() ? "TECHNICAL" : questionType.strip();
    }

    public QuestionBlueprint withRubric(AdaptiveRubric rubric) {
        if (rubric == null) throw new IllegalArgumentException("题目评分标准不能为空");
        if (rubric.difficulty() != difficulty || !rubric.knowledgePoint().equals(knowledgePoint)) {
            throw new IllegalArgumentException("评分标准必须与出题目标一致");
        }
        return new QuestionBlueprint(competency, knowledgePoint, difficulty, rubric.toMap(), origin, parentQuestionId);
    }
}