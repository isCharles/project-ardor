package com.projectardor.knowledge.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record KnowledgeResearchRequest(
        @NotBlank(message = "研究主题不能为空")
        @Size(max = 400, message = "研究主题不能超过 400 个字符")
        String query) {
}
