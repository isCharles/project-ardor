package com.projectardor.agent;

import java.util.UUID;

public record AgentContextReference(String type, UUID id, String label) {
}
