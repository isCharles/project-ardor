package com.projectardor.assistant.execution.service;

import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
    private final boolean promptCache;
    private final boolean cacheTools;

    public AnthropicAgentModelProtocol() {
        this(false, true);
    }

    @Autowired
    public AnthropicAgentModelProtocol(
            @Value("${app.model.prompt-cache.enabled:false}") boolean promptCache,
            @Value("${app.model.prompt-cache.tools:true}") boolean cacheTools) {
        this.promptCache = promptCache;
        this.cacheTools = cacheTools;
    }

    @Override public LlmProvider provider(){return LlmProvider.ANTHROPIC_COMPATIBLE;}
    @Override public ChatModel chat(LlmRuntimeConfig config,ModelReasoningPolicy.Profile profile,HttpClientBuilder transport,Duration timeout,int retries) {
        return chat(config, profile, transport, timeout, retries, List.of());
    }
    @Override public ChatModel chat(LlmRuntimeConfig config,ModelReasoningPolicy.Profile profile,HttpClientBuilder transport,
            Duration timeout,int retries,List<ChatModelListener> listeners) {
        ChatModel cached = chatModel(config, profile, transport, timeout, retries, listeners, promptCache);
        if (!promptCache) return cached;
        ChatModel plain = chatModel(config, profile, transport, timeout, retries, listeners, false);
        return PromptCacheFallbackModels.chat(cached, () -> plain, fingerprint(config));
    }
    @Override public StreamingChatModel streaming(LlmRuntimeConfig config,ModelReasoningPolicy.Profile profile,HttpClientBuilder transport,Duration timeout) {
        return streaming(config, profile, transport, timeout, List.of());
    }
    @Override public StreamingChatModel streaming(LlmRuntimeConfig config,ModelReasoningPolicy.Profile profile,
            HttpClientBuilder transport,Duration timeout,List<ChatModelListener> listeners) {
        StreamingChatModel cached = streamingModel(config, profile, transport, timeout, listeners, promptCache);
        if (!promptCache) return cached;
        StreamingChatModel plain = streamingModel(config, profile, transport, timeout, listeners, false);
        return PromptCacheFallbackModels.streaming(cached, () -> plain, fingerprint(config));
    }

    private ChatModel chatModel(LlmRuntimeConfig config, ModelReasoningPolicy.Profile profile,
            HttpClientBuilder transport, Duration timeout, int retries, List<ChatModelListener> listeners,
            boolean cache) {
        var builder = AnthropicChatModel.builder().httpClientBuilder(transport).baseUrl(sdkBaseUrl(config)).apiKey(config.apiKey())
                .modelName(config.model()).maxTokens(profile.maxTokens()).thinkingType(profile.thinkingType())
                .thinkingBudgetTokens(profile.budget()).customParameters(profile.extra())
                .returnThinking(profile.sendThinking()).sendThinking(profile.sendThinking())
                .timeout(timeout).maxRetries(retries).listeners(listeners);
        if (cache) builder.cacheSystemMessages(false).cacheTools(cacheTools);
        return builder.build();
    }

    private StreamingChatModel streamingModel(LlmRuntimeConfig config, ModelReasoningPolicy.Profile profile,
            HttpClientBuilder transport, Duration timeout, List<ChatModelListener> listeners, boolean cache) {
        var builder = AnthropicStreamingChatModel.builder().httpClientBuilder(transport).baseUrl(sdkBaseUrl(config)).apiKey(config.apiKey())
                .modelName(config.model()).maxTokens(profile.maxTokens()).thinkingType(profile.thinkingType())
                .thinkingBudgetTokens(profile.budget()).customParameters(profile.extra())
                .returnThinking(profile.sendThinking()).sendThinking(profile.sendThinking())
                .timeout(timeout).listeners(listeners);
        if (cache) builder.cacheSystemMessages(false).cacheTools(cacheTools);
        return builder.build();
    }

    private static String fingerprint(LlmRuntimeConfig config) {
        return config.provider() + "|" + config.baseUrl() + "|" + config.model();
    }

    // The stored connection is a service root, as in the JSON gateway.
    // This SDK appends /messages itself and therefore needs the /v1 prefix.
    private String sdkBaseUrl(LlmRuntimeConfig config) {return config.baseUrl()+"/v1";}
}
