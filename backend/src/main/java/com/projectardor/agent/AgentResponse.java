package com.projectardor.agent;

import java.util.List;

public record AgentResponse(String message, List<String> requestedClarifications) {
}

