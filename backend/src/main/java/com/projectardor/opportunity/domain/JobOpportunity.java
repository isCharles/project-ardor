package com.projectardor.opportunity.domain;

import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
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
     * Allowed status moves. The correction edges (back to AWAITING_RESULT, or from
     * AWAITING_RESULT back to INTERVIEWED) exist because a user can record the wrong
     * outcome and must be able to fix it without deleting the process.
     */
    private static final Map<OpportunityStatus, Set<OpportunityStatus>> TRANSITIONS = moves();

    private static Map<OpportunityStatus, Set<OpportunityStatus>> moves() {
        Map<OpportunityStatus, Set<OpportunityStatus>> map = new EnumMap<>(OpportunityStatus.class);
        map.put(OpportunityStatus.INVITED,
                EnumSet.of(OpportunityStatus.SCHEDULED, OpportunityStatus.INTERVIEWED,
                        OpportunityStatus.WITHDRAWN, OpportunityStatus.REJECTED, OpportunityStatus.NO_RESPONSE));
        map.put(OpportunityStatus.SCHEDULED,
                EnumSet.of(OpportunityStatus.INVITED, OpportunityStatus.INTERVIEWED,
                        OpportunityStatus.WITHDRAWN, OpportunityStatus.REJECTED, OpportunityStatus.NO_RESPONSE));
        map.put(OpportunityStatus.INTERVIEWED,
                EnumSet.of(OpportunityStatus.AWAITING_RESULT, OpportunityStatus.OFFER,
                        OpportunityStatus.REJECTED, OpportunityStatus.WITHDRAWN, OpportunityStatus.NO_RESPONSE));
        map.put(OpportunityStatus.AWAITING_RESULT,
                EnumSet.of(OpportunityStatus.INTERVIEWED, OpportunityStatus.OFFER,
                        OpportunityStatus.REJECTED, OpportunityStatus.WITHDRAWN, OpportunityStatus.NO_RESPONSE));
        for (OpportunityStatus terminal : OpportunityStatus.values()) {
            if (terminal.isTerminal()) map.put(terminal, EnumSet.of(OpportunityStatus.AWAITING_RESULT));
        }
        return Map.copyOf(map);
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
        this.status = OpportunityStatus.INVITED;
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

    public static boolean canMove(OpportunityStatus from, OpportunityStatus to) {
        Set<OpportunityStatus> allowed = from == null ? null : TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }

    public void transitionTo(OpportunityStatus target) {
        if (target == null) throw new IllegalArgumentException("面试状态不能为空");
        if (target == status) return;
        if (!canMove(status, target)) {
            throw new IllegalStateException("面试流程不能从 " + status + " 变更为 " + target);
        }
        status = target;
    }

    public void updateDetails(String company, String targetRole, String jdContent) {
        updateDetails(company, targetRole, null, jdContent);
    }

    public void updateDetails(String company, String targetRole, String platform, String jdContent) {
        if (company != null) {
            if (company.isBlank()) throw new IllegalArgumentException("公司不能为空");
            this.company = company.trim();
        }
        if (targetRole != null) this.targetRole = targetRole;
        if (platform != null) this.platform = platform;
        if (jdContent != null) this.jdContent = jdContent;
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
