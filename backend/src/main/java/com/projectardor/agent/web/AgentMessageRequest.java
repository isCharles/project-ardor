package com.projectardor.agent.web;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AgentMessageRequest(
        UUID conversationId,
        @NotBlank(message = "消息不能为空")
        @Size(max = 50000, message = "消息不能超过 50000 个字符")
        String message,
        String contextType,
        UUID contextId) {
}
