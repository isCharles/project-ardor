package com.projectardor.assistant.conversation;

import java.util.UUID;

public record AgentContextReference(String type, UUID id, String label) {
}
