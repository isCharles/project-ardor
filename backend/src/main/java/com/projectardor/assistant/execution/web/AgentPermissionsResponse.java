package com.projectardor.assistant.execution.web;

import java.util.List;

/** Approval mode plus legacy numeric projections; custom grants remain stored separately. */
public record AgentPermissionsResponse(int globalLevel, List<AgentModuleGrant> modules,
        com.projectardor.assistant.execution.service.AgentApprovalMode mode) {}
