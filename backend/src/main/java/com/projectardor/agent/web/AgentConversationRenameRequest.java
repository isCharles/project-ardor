package com.projectardor.agent.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AgentConversationRenameRequest(
        @NotBlank(message = "会话标题不能为空")
        @Size(max = 240, message = "会话标题不能超过 240 个字符")
        String title) {
}
