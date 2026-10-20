package com.projectardor.agent.web;

import java.util.List;
import java.util.UUID;

/** Owner-scoped read-only execution audit for one durable run. */
public record AgentRunAuditResponse(
        UUID runId,
        UUID assistantMessageId,
        List<AgentToolCallResponse> toolCalls,
        List<AgentProposalAuditResponse> proposals,
        List<AgentAuditEventResponse> auditEvents) {
}
