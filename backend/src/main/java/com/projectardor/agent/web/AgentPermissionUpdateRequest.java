package com.projectardor.agent.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record AgentPermissionUpdateRequest(@Min(0) @Max(3) int level) {}
