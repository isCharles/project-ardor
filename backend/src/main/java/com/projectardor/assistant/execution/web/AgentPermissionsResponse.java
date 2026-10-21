package com.projectardor.assistant.execution.web;

import java.util.List;

/** Global quick level plus the effective level of every module. */
public record AgentPermissionsResponse(int globalLevel, List<AgentModuleGrant> modules) {}
