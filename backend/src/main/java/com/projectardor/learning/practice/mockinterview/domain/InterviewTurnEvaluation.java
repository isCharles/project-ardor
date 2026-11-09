package com.projectardor.learning.practice.mockinterview.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "interview_turn_evaluations")
public class InterviewTurnEvaluation {
    @Id private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "interview_session_id", nullable = false) private UUID interviewSessionId;
    @Column(name = "interview_question_id", nullable = false, unique = true) private UUID interviewQuestionId;
    @Column(name = "interview_answer_id", nullable = false, unique = true) private UUID interviewAnswerId;
    @Enumerated(EnumType.STRING) @Column(name = "answer_kind", nullable = false, length = 24) private AnswerKind answerKind;
    @Column private Integer score;
    @Column(precision = 3, scale = 2) private BigDecimal confidence;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> evaluation;
    @Column(name = "model_name", length = 120) private String modelName;
    @Column(name = "prompt_version", nullable = false, length = 64) private String promptVersion;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected InterviewTurnEvaluation() {}

    private InterviewTurnEvaluation(UUID userId, UUID sessionId, UUID questionId, UUID answerId,
            AnswerKind answerKind, Integer score, BigDecimal confidence, Map<String, Object> evaluation,
            String modelName, String promptVersion) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.interviewSessionId = sessionId;
        this.interviewQuestionId = questionId;
        this.interviewAnswerId = answerId;
        this.answerKind = answerKind;
        this.score = score;
        this.confidence = confidence;
        this.evaluation = evaluation == null ? null : new LinkedHashMap<>(evaluation);
        this.modelName = modelName;
        this.promptVersion = promptVersion;
    }

    public static InterviewTurnEvaluation createScored(UUID userId, UUID sessionId, UUID questionId, UUID answerId,
            AnswerKind answerKind, int score, BigDecimal confidence, Map<String, Object> evaluation,
            String modelName, String promptVersion) {
        if (answerKind == null || answerKind == AnswerKind.CLARIFICATION) {
            throw new IllegalArgumentException("评分结果必须对应一种可评分的回答");
        }
        if (score < 0 || score > 4) throw new IllegalArgumentException("评分必须在 0 到 4 之间");
        if (confidence == null || confidence.signum() < 0 || confidence.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("置信度必须在 0 到 1 之间");
        }
        return new InterviewTurnEvaluation(userId, sessionId, questionId, answerId, answerKind, score,
                confidence, evaluation, modelName, promptVersion);
    }

    public static InterviewTurnEvaluation createClarification(UUID userId, UUID sessionId, UUID questionId,
            UUID answerId, Map<String, Object> evaluation, String modelName, String promptVersion) {
        return new InterviewTurnEvaluation(userId, sessionId, questionId, answerId, AnswerKind.CLARIFICATION,
                null, null, evaluation, modelName, promptVersion);
    }

    @PrePersist void onCreate() { Instant now = Instant.now(); createdAt = now; updatedAt = now; }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getInterviewSessionId() { return interviewSessionId; }
    public UUID getInterviewQuestionId() { return interviewQuestionId; }
    public UUID getInterviewAnswerId() { return interviewAnswerId; }
    public AnswerKind getAnswerKind() { return answerKind; }
    public Integer getScore() { return score; }
    public BigDecimal getConfidence() { return confidence; }
    public Map<String, Object> getEvaluation() { return evaluation == null ? Map.of() : Map.copyOf(evaluation); }
    public String getModelName() { return modelName; }
    public String getPromptVersion() { return promptVersion; }
    public Instant getCreatedAt() { return createdAt; }
}