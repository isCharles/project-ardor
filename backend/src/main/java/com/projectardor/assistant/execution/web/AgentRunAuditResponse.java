package com.projectardor.assistant.execution.web;

import java.util.List;
import java.util.UUID;
import com.projectardor.assistant.proposal.web.AgentProposalAuditResponse;

/** Owner-scoped read-only execution audit for one durable run. */
public record AgentRunAuditResponse(
        UUID runId,
        UUID assistantMessageId,
        List<AgentToolCallResponse> toolCalls,
        List<AgentProposalAuditResponse> proposals,
        List<AgentAuditEventResponse> auditEvents) {
}
