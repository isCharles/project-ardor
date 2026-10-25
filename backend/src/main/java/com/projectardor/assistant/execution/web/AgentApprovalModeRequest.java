package com.projectardor.assistant.execution.web;

import com.projectardor.assistant.execution.service.AgentApprovalMode;
import jakarta.validation.constraints.NotNull;

public record AgentApprovalModeRequest(@NotNull AgentApprovalMode mode) {}
