package com.projectardor.recap.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.*;

@Entity
@Table(name = "interview_recap_questions")
public class InterviewRecapQuestion {
    @Id private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "recap_id", nullable = false) private UUID recapId;
    @Column(name = "sequence_number", nullable = false) private int sequenceNumber;
    @Column(name = "question_text", nullable = false, columnDefinition = "text") private String questionText;
    @Column(name = "candidate_answer", columnDefinition = "text") private String candidateAnswer;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "follow_ups", nullable = false, columnDefinition = "jsonb") private List<String> followUps = new ArrayList<>();
    @Column(nullable = false, columnDefinition = "text") private String assessment;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private QuestionPerformance performance;
    @Column(name = "weakness_reason", columnDefinition = "text") private String weaknessReason;
    @Column(name = "better_answer", columnDefinition = "text") private String betterAnswer;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private List<String> tags = new ArrayList<>();
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected InterviewRecapQuestion() {}
    private InterviewRecapQuestion(UUID userId, UUID recapId, int sequenceNumber, String questionText,
            String candidateAnswer, List<String> followUps, String assessment, QuestionPerformance performance,
            String weaknessReason, String betterAnswer, List<String> tags) {
        this.id = UUID.randomUUID(); this.userId = userId; this.recapId = recapId; this.sequenceNumber = sequenceNumber;
        this.questionText = questionText; this.candidateAnswer = candidateAnswer; this.followUps = new ArrayList<>(followUps);
        this.assessment = assessment; this.performance = performance; this.weaknessReason = weaknessReason;
        this.betterAnswer = betterAnswer; this.tags = new ArrayList<>(tags);
    }
    public static InterviewRecapQuestion create(UUID userId, UUID recapId, int sequenceNumber, String questionText,
            String candidateAnswer, List<String> followUps, String assessment, QuestionPerformance performance,
            String weaknessReason, String betterAnswer, List<String> tags) {
        return new InterviewRecapQuestion(userId, recapId, sequenceNumber, questionText, candidateAnswer, followUps,
                assessment, performance, weaknessReason, betterAnswer, tags);
    }
    @PrePersist void onCreate() { Instant now = Instant.now(); createdAt = now; updatedAt = now; }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
    public UUID getId() { return id; }
    public UUID getRecapId() { return recapId; }
    public int getSequenceNumber() { return sequenceNumber; }
    public String getQuestionText() { return questionText; }
    public String getCandidateAnswer() { return candidateAnswer; }
    public List<String> getFollowUps() { return List.copyOf(followUps); }
    public String getAssessment() { return assessment; }
    public QuestionPerformance getPerformance() { return performance; }
    public String getWeaknessReason() { return weaknessReason; }
    public String getBetterAnswer() { return betterAnswer; }
    public List<String> getTags() { return List.copyOf(tags); }
}
