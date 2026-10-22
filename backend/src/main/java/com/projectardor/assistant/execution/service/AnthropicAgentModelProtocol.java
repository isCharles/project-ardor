package com.projectardor.assistant.execution.service;

import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;
import com.projectardor.platform.model.llm.domain.LlmProvider;
import com.projectardor.platform.model.llm.service.LlmConfigService.LlmRuntimeConfig;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;

@Component
public class AnthropicAgentModelProtocol implements AgentModelProtocol {
    @Override public LlmProvider provider(){return LlmProvider.ANTHROPIC_COMPATIBLE;}
    @Override public ChatModel chat(LlmRuntimeConfig config,ModelReasoningPolicy.Profile profile,HttpClientBuilder transport,Duration timeout,int retries) {
        return chat(config, profile, transport, timeout, retries, List.of());
    }
    @Override public ChatModel chat(LlmRuntimeConfig config,ModelReasoningPolicy.Profile profile,HttpClientBuilder transport,
            Duration timeout,int retries,List<ChatModelListener> listeners) {
        return AnthropicChatModel.builder().httpClientBuilder(transport).baseUrl(sdkBaseUrl(config)).apiKey(config.apiKey())
                .modelName(config.model()).maxTokens(profile.maxTokens()).thinkingType(profile.thinkingType())
                .thinkingBudgetTokens(profile.budget()).customParameters(profile.extra())
                .returnThinking(profile.sendThinking()).sendThinking(profile.sendThinking())
                .timeout(timeout).maxRetries(retries).listeners(listeners).build();
    }
    @Override public StreamingChatModel streaming(LlmRuntimeConfig config,ModelReasoningPolicy.Profile profile,HttpClientBuilder transport,Duration timeout) {
        return streaming(config, profile, transport, timeout, List.of());
    }
    @Override public StreamingChatModel streaming(LlmRuntimeConfig config,ModelReasoningPolicy.Profile profile,
            HttpClientBuilder transport,Duration timeout,List<ChatModelListener> listeners) {
        return AnthropicStreamingChatModel.builder().httpClientBuilder(transport).baseUrl(sdkBaseUrl(config)).apiKey(config.apiKey())
                .modelName(config.model()).maxTokens(profile.maxTokens()).thinkingType(profile.thinkingType())
                .thinkingBudgetTokens(profile.budget()).customParameters(profile.extra())
                .returnThinking(profile.sendThinking()).sendThinking(profile.sendThinking())
                .timeout(timeout).listeners(listeners).build();
    }
    // The stored connection is a service root, as in the JSON gateway.
    // This SDK appends /messages itself and therefore needs the /v1 prefix.
    private String sdkBaseUrl(LlmRuntimeConfig config) {return config.baseUrl()+"/v1";}
}
