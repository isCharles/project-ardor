package com.projectardor.assistant.proposal.web;

import java.time.Instant;
import java.util.UUID;

import com.projectardor.assistant.execution.service.AgentExecutionAuditStore;

/** Presentation lifecycle of a proposal card. A decision is not proof that the mutation succeeded. */
public record AgentProposalAuditResponse(
        UUID proposalId,
        UUID runId,
        UUID messageId,
        String kind,
        UUID targetId,
        String status,
        String resolution,
        Instant createdAt,
        Instant resolvedAt) {

    public static AgentProposalAuditResponse from(AgentExecutionAuditStore.Proposal proposal) {
        return new AgentProposalAuditResponse(proposal.id(), proposal.runId(), proposal.messageId(),
                proposal.kind(), proposal.targetId(), proposal.status(), proposal.resolution(),
                proposal.createdAt(), proposal.resolvedAt());
    }
}
