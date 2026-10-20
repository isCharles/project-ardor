package com.projectardor.agent.web;

import java.time.Instant;
import java.util.UUID;

import com.projectardor.agent.service.AgentExecutionAuditStore;

/** Legacy audit-table event, kept readable per run with its approval source. */
public record AgentAuditEventResponse(
        UUID id,
        String tool,
        String module,
        String capability,
        String approvalSource,
        String argumentsDigest,
        Instant createdAt) {

    public static AgentAuditEventResponse from(AgentExecutionAuditStore.AuditEvent event) {
        return new AgentAuditEventResponse(event.id(), event.tool(), event.module(), event.capability(),
                event.approvalSource(), event.argumentsDigest(), event.createdAt());
    }
}
