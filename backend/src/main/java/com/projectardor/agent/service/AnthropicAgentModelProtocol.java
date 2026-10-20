package com.projectardor.agent.service;

import java.time.Duration;
import org.springframework.stereotype.Component;
import com.projectardor.llm.domain.LlmProvider;
import com.projectardor.llm.service.LlmConfigService.LlmRuntimeConfig;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;

@Component
public class AnthropicAgentModelProtocol implements AgentModelProtocol {
    @Override public LlmProvider provider(){return LlmProvider.ANTHROPIC_COMPATIBLE;}
    @Override public ChatModel chat(LlmRuntimeConfig config,ModelReasoningPolicy.Profile profile,HttpClientBuilder transport,Duration timeout,int retries) {
        return AnthropicChatModel.builder().httpClientBuilder(transport).baseUrl(sdkBaseUrl(config)).apiKey(config.apiKey())
                .modelName(config.model()).maxTokens(profile.maxTokens()).thinkingType(profile.thinkingType())
                .thinkingBudgetTokens(profile.budget()).customParameters(profile.extra())
                .returnThinking(profile.sendThinking()).sendThinking(profile.sendThinking()).timeout(timeout).maxRetries(retries).build();
    }
    @Override public StreamingChatModel streaming(LlmRuntimeConfig config,ModelReasoningPolicy.Profile profile,HttpClientBuilder transport,Duration timeout) {
        return AnthropicStreamingChatModel.builder().httpClientBuilder(transport).baseUrl(sdkBaseUrl(config)).apiKey(config.apiKey())
                .modelName(config.model()).maxTokens(profile.maxTokens()).thinkingType(profile.thinkingType())
                .thinkingBudgetTokens(profile.budget()).customParameters(profile.extra())
                .returnThinking(profile.sendThinking()).sendThinking(profile.sendThinking()).timeout(timeout).build();
    }
    // The stored connection is a service root, as in the JSON gateway.
    // This SDK appends /messages itself and therefore needs the /v1 prefix.
    private String sdkBaseUrl(LlmRuntimeConfig config) {return config.baseUrl()+"/v1";}
}
