package com.projectardor.learning.practice.mockinterview.domain;

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
import jakarta.persistence.Table;

@Entity
@Table(name = "interview_policy_decisions")
public class InterviewPolicyDecision {
    @Id private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "interview_session_id", nullable = false) private UUID interviewSessionId;
    @Column(name = "sequence_number", nullable = false) private int sequenceNumber;
    @Column(name = "interview_question_id") private UUID interviewQuestionId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private PolicyAction action;
    @Column(nullable = false, columnDefinition = "text") private String reason;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> decision = new LinkedHashMap<>();
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected InterviewPolicyDecision() {}

    private InterviewPolicyDecision(UUID userId, UUID sessionId, int sequenceNumber, UUID questionId,
            PolicyAction action, String reason, Map<String, Object> decision) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.interviewSessionId = sessionId;
        this.sequenceNumber = sequenceNumber;
        this.interviewQuestionId = questionId;
        this.action = action;
        this.reason = reason;
        this.decision = new LinkedHashMap<>(decision);
    }

    public static InterviewPolicyDecision create(UUID userId, UUID sessionId, int sequenceNumber, UUID questionId,
            PolicyAction action, String reason, Map<String, Object> decision) {
        if (sequenceNumber < 1) throw new IllegalArgumentException("决策序号必须为正数");
        if (action == null) throw new IllegalArgumentException("决策动作不能为空");
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("决策理由不能为空");
        return new InterviewPolicyDecision(userId, sessionId, sequenceNumber, questionId, action,
                reason.strip(), decision);
    }

    @PrePersist void onCreate() { createdAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getInterviewSessionId() { return interviewSessionId; }
    public int getSequenceNumber() { return sequenceNumber; }
    public UUID getInterviewQuestionId() { return interviewQuestionId; }
    public PolicyAction getAction() { return action; }
    public String getReason() { return reason; }
    public Map<String, Object> getDecision() { return Map.copyOf(decision); }
    public Instant getCreatedAt() { return createdAt; }
}