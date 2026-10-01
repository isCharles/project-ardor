package com.projectardor.recap.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
@Table(name = "interview_replay_attempts")
public class InterviewReplayAttempt {
    @Id private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "recap_question_id", nullable = false) private UUID recapQuestionId;
    @Column(name = "request_id", nullable = false) private UUID requestId;
    @Column(name = "answer_text", nullable = false, columnDefinition = "text") private String answerText;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) private ReplayVerdict verdict;
    @Column(nullable = false, columnDefinition = "text") private String comparison;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private List<String> improvements = new ArrayList<>();
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "remaining_gaps", nullable = false, columnDefinition = "jsonb") private List<String> remainingGaps = new ArrayList<>();
    @Column(name = "next_challenge", columnDefinition = "text") private String nextChallenge;
    @Column(name = "challenge_text", columnDefinition = "text") private String challengeText;
    @Column(name = "retest_task_id") private UUID retestTaskId;
    @Column(name = "model_name", length = 120) private String modelName;
    @Column(name = "prompt_version", nullable = false, length = 40) private String promptVersion;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected InterviewReplayAttempt() {}

    private InterviewReplayAttempt(UUID userId, UUID recapQuestionId, UUID requestId, String answerText,
            ReplayVerdict verdict, String comparison, List<String> improvements, List<String> remainingGaps,
            String nextChallenge, String modelName) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.recapQuestionId = recapQuestionId;
        this.requestId = requestId;
        this.answerText = answerText;
        this.verdict = verdict;
        this.comparison = comparison;
        this.improvements = new ArrayList<>(improvements);
        this.remainingGaps = new ArrayList<>(remainingGaps);
        this.nextChallenge = nextChallenge;
        this.modelName = modelName;
        this.promptVersion = "interview-replay-v2";
    }

    public static InterviewReplayAttempt create(UUID userId, UUID recapQuestionId, UUID requestId,
            String answerText, ReplayVerdict verdict, String comparison, List<String> improvements,
            List<String> remainingGaps, String nextChallenge, String modelName) {
        return create(userId, recapQuestionId, requestId, answerText, verdict, comparison,
                improvements, remainingGaps, nextChallenge, modelName, null, null);
    }

    public static InterviewReplayAttempt create(UUID userId, UUID recapQuestionId, UUID requestId,
            String answerText, ReplayVerdict verdict, String comparison, List<String> improvements,
            List<String> remainingGaps, String nextChallenge, String modelName,
            String challengeText, UUID retestTaskId) {
        InterviewReplayAttempt attempt = new InterviewReplayAttempt(userId, recapQuestionId, requestId, answerText, verdict,
                comparison, improvements, remainingGaps, nextChallenge, modelName);
        attempt.challengeText = challengeText;
        attempt.retestTaskId = retestTaskId;
        return attempt;
    }

    @PrePersist void onCreate() { createdAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getRecapQuestionId() { return recapQuestionId; }
    public UUID getRequestId() { return requestId; }
    public String getAnswerText() { return answerText; }
    public ReplayVerdict getVerdict() { return verdict; }
    public String getComparison() { return comparison; }
    public List<String> getImprovements() { return List.copyOf(improvements); }
    public List<String> getRemainingGaps() { return List.copyOf(remainingGaps); }
    public String getNextChallenge() { return nextChallenge; }
    public String getChallengeText() { return challengeText; }
    public UUID getRetestTaskId() { return retestTaskId; }
    public String getModelName() { return modelName; }
    public Instant getCreatedAt() { return createdAt; }
}
