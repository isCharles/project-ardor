package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.projectardor.platform.model.llm.domain.LlmProvider;
import com.projectardor.platform.model.llm.service.LlmConfigService;

import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;

class AgentRequestTokensTests {
    @Test
    void estimatesMultimodalMessagesWithoutTokenizingBase64ImageData() {
        var config = new LlmConfigService.LlmRuntimeConfig(
                LlmProvider.OPENAI_COMPATIBLE, "https://example.invalid", "gpt-4o", "synthetic-only");
        var estimator = AgentRequestTokens.estimator(config);
        var message = UserMessage.from(List.of(
                TextContent.from("描述这张图片"),
                ImageContent.from("aGVsbG8=", "image/png")));

        int estimated = estimator.estimateTokenCountInMessage(message);

        assertThat(estimated).isBetween(10, 10_000);
    }
}
