package com.projectardor.assistant.execution.web;

/** Effective level for one module and whether the user set it explicitly. */
public record AgentModuleGrant(String module, int level, boolean overridden) {}
