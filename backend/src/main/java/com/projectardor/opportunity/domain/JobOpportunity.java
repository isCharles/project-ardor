package com.projectardor.opportunity.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * A real interview process for one company/role. Mock sessions are practice and
 * recaps are post-hoc review; this entity is the thing that actually terminates.
 *
 * <p>{@code targetRole} carries the posting title the user sees in their notes
 * (for example "后端开发实习生—国际商业化产品与技术"); there is deliberately no
 * separate title field, because a process has exactly one role.
 */
@Entity
@Table(name = "job_opportunities")
public class JobOpportunity {
    /**
     * Any state can move to any other. With only four coarse states there is no
     * forward-only ordering worth enforcing, and a mis-clicked outcome must stay
     * editable — a rejected process can be re-opened as scheduled if the rejection
     * was recorded by mistake. Only null is rejected; same-state moves are no-ops.
     */
    public static boolean canMove(OpportunityStatus from, OpportunityStatus to) {
        return from != null && to != null;
    }

    @Id private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(nullable = false, length = 160) private String company;
    @Column(name = "target_role", length = 160) private String targetRole;
    @Column(length = 80) private String platform;
    @Column(name = "jd_content", columnDefinition = "text") private String jdContent;
    @Column(name = "invite_content", columnDefinition = "text") private String inviteContent;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private OpportunityStatus status;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected JobOpportunity() {}

    private JobOpportunity(UUID userId, String company, String targetRole, String platform,
            String jdContent, String inviteContent) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.company = company;
        this.targetRole = targetRole;
        this.platform = platform;
        this.jdContent = jdContent;
        this.inviteContent = inviteContent;
        this.status = OpportunityStatus.SCHEDULED;
    }

    public static JobOpportunity create(UUID userId, String company, String targetRole,
            String jdContent, String inviteContent) {
        return create(userId, company, targetRole, null, jdContent, inviteContent);
    }

    public static JobOpportunity create(UUID userId, String company, String targetRole, String platform,
            String jdContent, String inviteContent) {
        if (company == null || company.isBlank()) throw new IllegalArgumentException("公司不能为空");
        return new JobOpportunity(userId, company.trim(), targetRole, platform, jdContent, inviteContent);
    }

    public void transitionTo(OpportunityStatus target) {
        if (target == null) throw new IllegalArgumentException("面试状态不能为空");
        status = target;
    }

    public void updateDetails(String company, String targetRole, String jdContent) {
        updateDetails(company, targetRole, null, jdContent, null);
    }

    public void updateDetails(String company, String targetRole, String platform, String jdContent) {
        updateDetails(company, targetRole, platform, jdContent, null);
    }

    public void updateDetails(String company, String targetRole, String platform, String jdContent,
            String inviteContent) {
        if (company != null) {
            if (company.isBlank()) throw new IllegalArgumentException("公司不能为空");
            this.company = company.trim();
        }
        if (targetRole != null) this.targetRole = targetRole;
        if (platform != null) this.platform = platform;
        if (jdContent != null) this.jdContent = jdContent;
        if (inviteContent != null) this.inviteContent = inviteContent;
    }

    @PrePersist void onCreate() { Instant now = Instant.now(); createdAt = now; updatedAt = now; }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getCompany() { return company; }
    public String getTargetRole() { return targetRole; }
    public String getPlatform() { return platform; }
    public String getJdContent() { return jdContent; }
    public String getInviteContent() { return inviteContent; }
    public OpportunityStatus getStatus() { return status; }
    public boolean isTerminal() { return status.isTerminal(); }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
