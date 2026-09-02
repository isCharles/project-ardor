package com.projectardor.interview.domain;

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

    public UUID getInterviewQuestionId() { return interviewQuestionId; }
    public String getAnswerText() { return answerText; }
    public Integer getDurationSeconds() { return durationSeconds; }
}
