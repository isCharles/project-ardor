package com.projectardor.assistant.execution.service;

import com.projectardor.platform.model.llm.domain.LlmProvider;
import com.projectardor.platform.model.llm.service.LlmConfigService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;

/** Shared estimates for compression, request telemetry and the pre-send limit fence. */
public final class AgentRequestTokens {
    public static final int IMAGE_TOKENS = 1_105;
    private AgentRequestTokens() {}
    public static TokenCountEstimator estimator(LlmConfigService.LlmRuntimeConfig config) {
        if (config.provider() == LlmProvider.OPENAI_COMPATIBLE) {
            try { return imageAware(new OpenAiTokenCountEstimator(config.model())); }
            catch (RuntimeException unsupported) { /* Unknown tokenizer: explicitly approximate. */ }
        }
        return imageAware(approximate());
    }
    private static TokenCountEstimator imageAware(TokenCountEstimator delegate) {
        return new TokenCountEstimator() {
            @Override public int estimateTokenCountInText(String text) { return delegate.estimateTokenCountInText(text); }
            @Override public int estimateTokenCountInMessage(ChatMessage message) {
                if (!(message instanceof UserMessage user)
                        || user.contents().stream().noneMatch(ImageContent.class::isInstance)) {
                    return delegate.estimateTokenCountInMessage(message);
                }
                long total = 4;
                for (var content : user.contents()) {
                    if (content instanceof TextContent text) total += delegate.estimateTokenCountInText(text.text());
                    else if (content instanceof ImageContent) total += IMAGE_TOKENS;
                }
                return (int) Math.min(Integer.MAX_VALUE, total);
            }
            @Override public int estimateTokenCountInMessages(Iterable<ChatMessage> messages) {
                long total = 0;
                for (var message : messages) total += estimateTokenCountInMessage(message);
                return (int) Math.min(Integer.MAX_VALUE, total);
            }
        };
    }
    static TokenCountEstimator approximate() {
        return new TokenCountEstimator() {
            @Override public int estimateTokenCountInText(String text) { return text == null ? 0 : (int) ((text.length() + 1L) / 2); }
            @Override public int estimateTokenCountInMessage(ChatMessage message) { return 4 + estimateTokenCountInText(message.toString()); }
            @Override public int estimateTokenCountInMessages(Iterable<ChatMessage> messages) {
                long total = 0;
                for (var message : messages) total += estimateTokenCountInMessage(message);
                return (int) Math.min(Integer.MAX_VALUE, total);
            }
        };
    }
    static long estimate(ChatRequest request, TokenCountEstimator estimator) {
        var counter = estimator == null ? approximate() : estimator;
        long total = 0;
        for (var message : request.messages()) total += counter.estimateTokenCountInMessage(message);
        if (request.toolSpecifications() != null)
            for (var tool : request.toolSpecifications()) total += counter.estimateTokenCountInText(tool.toString());
        if (request.responseFormat() != null) total += counter.estimateTokenCountInText(request.responseFormat().toString());
        return total;
    }
}
