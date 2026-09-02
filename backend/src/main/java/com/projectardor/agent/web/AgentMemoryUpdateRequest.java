package com.projectardor.agent.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AgentMemoryUpdateRequest(
        @NotNull(message = "记忆内容不能为空")
        @Size(max = 4000, message = "总体记忆不能超过 4000 个字符")
        String content) {
}
