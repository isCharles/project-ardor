package com.projectardor.assistant;

import java.util.List;

public record AgentResponse(String message, List<String> requestedClarifications) {
}

