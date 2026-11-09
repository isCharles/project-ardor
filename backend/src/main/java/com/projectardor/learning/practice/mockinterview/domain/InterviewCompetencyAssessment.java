package com.projectardor.learning.practice.mockinterview.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
@Table(name = "interview_competency_assessments")
public class InterviewCompetencyAssessment {
    @Id private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "interview_session_id", nullable = false) private UUID interviewSessionId;
    @Column(nullable = false, length = 160) private String competency;
    @Column(name = "jd_priority", nullable = false) private int jdPriority;
    @Column(name = "estimated_level") private Integer estimatedLevel;
    @Column(nullable = false, precision = 3, scale = 2) private BigDecimal confidence;
    @Column(name = "evidence_count", nullable = false) private int evidenceCount;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private CompetencyState state;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> strengths = new ArrayList<>();
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "knowledge_gaps", nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> knowledgeGaps = new ArrayList<>();
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected InterviewCompetencyAssessment() {}

    private InterviewCompetencyAssessment(UUID userId, UUID sessionId, String competency, int jdPriority) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.interviewSessionId = sessionId;
        this.competency = competency;
        this.jdPriority = jdPriority;
        this.confidence = BigDecimal.ZERO;
        this.evidenceCount = 0;
        this.state = CompetencyState.UNVERIFIED;
    }

    public static InterviewCompetencyAssessment create(UUID userId, UUID sessionId, String competency, int jdPriority) {
        if (competency == null || competency.isBlank()) throw new IllegalArgumentException("能力维度不能为空");
        if (jdPriority < 1 || jdPriority > 5) throw new IllegalArgumentException("JD 优先级必须在 1 到 5 之间");
        return new InterviewCompetencyAssessment(userId, sessionId, competency.strip(), jdPriority);
    }

    /** An untested competency keeps a null level: only evidence may fill it in. */
    public void recordEvidence(Integer estimatedLevel, BigDecimal confidence, int evidenceCount,
            CompetencyState state, List<Map<String, Object>> strengths, List<Map<String, Object>> knowledgeGaps) {
        if (state == null) throw new IllegalArgumentException("能力状态不能为空");
        if (state == CompetencyState.UNVERIFIED && estimatedLevel != null) {
            throw new IllegalArgumentException("未验证的能力不能给出估计等级");
        }
        if (state == CompetencyState.VERIFIED && estimatedLevel == null) {
            throw new IllegalArgumentException("已验证的能力必须给出估计等级");
        }
        if (estimatedLevel != null && (estimatedLevel < 1 || estimatedLevel > 5)) {
            throw new IllegalArgumentException("估计等级必须在 1 到 5 之间");
        }
        if (confidence == null || confidence.signum() < 0 || confidence.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("置信度必须在 0 到 1 之间");
        }
        if (evidenceCount < 0) throw new IllegalArgumentException("证据数不能为负数");
        this.estimatedLevel = estimatedLevel;
        this.confidence = confidence;
        this.evidenceCount = evidenceCount;
        this.state = state;
        this.strengths = copyEntries(strengths);
        this.knowledgeGaps = copyEntries(knowledgeGaps);
    }

    private static List<Map<String, Object>> copyEntries(List<Map<String, Object>> entries) {
        List<Map<String, Object>> copy = new ArrayList<>();
        if (entries != null) {
            for (Map<String, Object> entry : entries) {
                if (entry != null) copy.add(Map.copyOf(entry));
            }
        }
        return copy;
    }

    @PrePersist void onCreate() { Instant now = Instant.now(); createdAt = now; updatedAt = now; }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getInterviewSessionId() { return interviewSessionId; }
    public String getCompetency() { return competency; }
    public int getJdPriority() { return jdPriority; }
    public Integer getEstimatedLevel() { return estimatedLevel; }
    public BigDecimal getConfidence() { return confidence; }
    public int getEvidenceCount() { return evidenceCount; }
    public CompetencyState getState() { return state; }
    public List<Map<String, Object>> getStrengths() { return List.copyOf(strengths); }
    public List<Map<String, Object>> getKnowledgeGaps() { return List.copyOf(knowledgeGaps); }
    public Instant getUpdatedAt() { return updatedAt; }
}