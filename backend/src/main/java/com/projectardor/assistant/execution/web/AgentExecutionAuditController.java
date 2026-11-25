package com.projectardor.assistant.execution.web;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.projectardor.assistant.execution.service.AgentExecutionAuditStore;
import com.projectardor.assistant.execution.service.AgentDeletionConfirmations;
import com.projectardor.assistant.execution.service.AgentRunStore;
import com.projectardor.assistant.tools.CareerAgentTools.PendingConfirmation;
import com.projectardor.platform.identity.auth.security.ArdorPrincipal;
import com.projectardor.assistant.proposal.web.AgentProposalAuditResponse;

/**
 * Read-only execution/audit views for one owned agent run.
 *
 * <p>Ownership is resolved from the authenticated principal and checked through the durable run
 * row first, so another user's run is a 404. Audit rows carry only digests and bounded references;
 * the live deletion view returns only the card already generated for that owned run. Neither view
 * grants permission or proves that a business mutation succeeded.
 */
@RestController
@RequestMapping("/api/agent/runs")
public class AgentExecutionAuditController {

    private final AgentRunStore runs;
    private final AgentExecutionAuditStore executionAudit;
    private final AgentDeletionConfirmations deletionConfirmations;

    public AgentExecutionAuditController(AgentRunStore runs, AgentExecutionAuditStore executionAudit,
            AgentDeletionConfirmations deletionConfirmations) {
        this.runs = runs;
        this.executionAudit = executionAudit;
        this.deletionConfirmations = deletionConfirmations;
    }

    @GetMapping("/{requestId}/tool-calls")
    public List<AgentToolCallResponse> toolCalls(@AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID requestId) {
        AgentRunStore.AgentRun run = ownedRun(principal, requestId);
        return executionAudit.toolCalls(principal.userId(), run.id()).stream()
                .map(AgentToolCallResponse::from).toList();
    }

    @GetMapping("/{requestId}/deletion-proposals")
    public List<AgentDeletionProposalResponse> deletionProposals(@AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID requestId) {
        ownedRun(principal, requestId);
        return deletionConfirmations.pending(principal.userId(), requestId).stream()
                .map(AgentDeletionProposalResponse::from).toList();
    }

    @GetMapping("/{requestId}/audit")
    public AgentRunAuditResponse audit(@AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID requestId) {
        AgentRunStore.AgentRun run = ownedRun(principal, requestId);
        return new AgentRunAuditResponse(run.id(), run.assistantMessageId(),
                executionAudit.toolCalls(principal.userId(), run.id()).stream()
                        .map(AgentToolCallResponse::from).toList(),
                executionAudit.proposals(principal.userId(), run.id()).stream()
                        .map(AgentProposalAuditResponse::from).toList(),
                executionAudit.auditEvents(principal.userId(), run.id()).stream()
                        .map(AgentAuditEventResponse::from).toList());
    }

    private AgentRunStore.AgentRun ownedRun(ArdorPrincipal principal, UUID requestId) {
        // Throws 404 for an unknown or foreign run without revealing which case it was.
        return runs.get(principal.userId(), requestId);
    }

    public record AgentDeletionProposalResponse(String kind, UUID targetId, String label, String detail,
            String endpoint, List<UUID> targetIds, UUID proposalId) {
        static AgentDeletionProposalResponse from(PendingConfirmation confirmation) {
            return new AgentDeletionProposalResponse(confirmation.kind(), confirmation.targetId(),
                    confirmation.label(), confirmation.detail(), confirmation.endpoint(),
                    confirmation.targetIds(), confirmation.proposalId());
        }
    }
}
