package com.projectardor.learning.practice.mockinterview.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "interview_answers")
public class InterviewAnswer {
    @Id private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "interview_session_id", nullable = false) private UUID interviewSessionId;
    @Column(name = "interview_question_id", nullable = false, unique = true) private UUID interviewQuestionId;
    @Column(name = "answer_text", nullable = false, columnDefinition = "text") private String answerText;
    @Column(name = "duration_seconds") private Integer durationSeconds;
    @Column(name = "evaluation_attempts", nullable = false) private short evaluationAttempts;
    @Column(name = "evaluation_error", length = 64) private String evaluationError;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected InterviewAnswer() {}

    private InterviewAnswer(
            UUID userId, UUID interviewSessionId, UUID questionId,
            String answerText, Integer durationSeconds) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.interviewSessionId = interviewSessionId;
        this.interviewQuestionId = questionId;
        this.answerText = answerText;
        this.durationSeconds = durationSeconds;
    }

    public static InterviewAnswer create(
            UUID userId, UUID interviewSessionId, UUID questionId,
            String answerText, Integer durationSeconds) {
        return new InterviewAnswer(userId, interviewSessionId, questionId, answerText, durationSeconds);
    }

    @PrePersist void onCreate() { Instant now = Instant.now(); createdAt = now; updatedAt = now; }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }

    /** The answer survives a failed scoring call so the turn can be retried instead of lost. */
    public void recordEvaluationFailure(String errorCode) {
        evaluationAttempts++;
        evaluationError = errorCode;
    }

    public void clearEvaluationFailure() {
        evaluationError = null;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getInterviewSessionId() { return interviewSessionId; }
    public UUID getInterviewQuestionId() { return interviewQuestionId; }
    public String getAnswerText() { return answerText; }
    public Integer getDurationSeconds() { return durationSeconds; }
    public int getEvaluationAttempts() { return evaluationAttempts; }
    public String getEvaluationError() { return evaluationError; }
}
