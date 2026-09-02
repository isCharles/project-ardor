package com.projectardor.interview.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "interview_questions")
public class InterviewQuestion {
    @Id private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "interview_session_id", nullable = false) private UUID interviewSessionId;
    @Column(name = "sequence_number", nullable = false) private int sequenceNumber;
    @Column(name = "question_text", nullable = false, columnDefinition = "text") private String questionText;
    @Column(name = "question_type", length = 64) private String questionType;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evaluation_criteria", nullable = false, columnDefinition = "jsonb")
    private List<String> evaluationCriteria = new ArrayList<>();
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected InterviewQuestion() {}

    private InterviewQuestion(
            UUID userId, UUID interviewSessionId, int sequenceNumber,
            String questionText, String questionType, List<String> criteria) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.interviewSessionId = interviewSessionId;
        this.sequenceNumber = sequenceNumber;
        this.questionText = questionText;
        this.questionType = questionType;
        this.evaluationCriteria = new ArrayList<>(criteria);
    }

    public static InterviewQuestion create(
            UUID userId, UUID interviewSessionId, int sequenceNumber,
            String questionText, String questionType, List<String> criteria) {
        return new InterviewQuestion(userId, interviewSessionId, sequenceNumber, questionText, questionType, criteria);
    }

    @PrePersist void onCreate() { Instant now = Instant.now(); createdAt = now; updatedAt = now; }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getInterviewSessionId() { return interviewSessionId; }
    public int getSequenceNumber() { return sequenceNumber; }
    public String getQuestionText() { return questionText; }
    public String getQuestionType() { return questionType; }
    public List<String> getEvaluationCriteria() { return List.copyOf(evaluationCriteria); }
}
