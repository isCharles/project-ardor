package com.projectardor.common.json;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class LlmJsonParser {

    private final ObjectMapper objectMapper;

    public LlmJsonParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JsonNode parseObject(String content) {
        String normalized = content.strip();
        if (normalized.startsWith("```")) {
            int firstLineEnd = normalized.indexOf('\n');
            int closingFence = normalized.lastIndexOf("```");
            if (firstLineEnd >= 0 && closingFence > firstLineEnd) {
                normalized = normalized.substring(firstLineEnd + 1, closingFence).strip();
            }
        }
        int firstBrace = normalized.indexOf('{');
        int lastBrace = normalized.lastIndexOf('}');
        if (firstBrace < 0 || lastBrace <= firstBrace) {
            throw new IllegalStateException("LLM 未返回有效的 JSON 对象");
        }
        try {
            JsonNode node = objectMapper.readTree(normalized.substring(firstBrace, lastBrace + 1));
            if (!node.isObject()) {
                throw new IllegalStateException("LLM 返回的 JSON 不是对象");
            }
            return node;
        } catch (JacksonException exception) {
            throw new IllegalStateException("LLM 返回的 JSON 无法解析", exception);
        }
    }
}
