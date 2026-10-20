package com.projectardor.agent.service;

import java.time.Duration;
import org.springframework.stereotype.Component;
import com.projectardor.platform.model.llm.domain.LlmProvider;
import com.projectardor.platform.model.llm.service.LlmConfigService.LlmRuntimeConfig;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

@Component
public class OpenAiAgentModelProtocol implements AgentModelProtocol {
    @Override public LlmProvider provider(){return LlmProvider.OPENAI_COMPATIBLE;}
    @Override public ChatModel chat(LlmRuntimeConfig config,ModelReasoningPolicy.Profile profile,HttpClientBuilder transport,Duration timeout,int retries) {
        return OpenAiChatModel.builder().httpClientBuilder(transport).baseUrl(config.baseUrl()).apiKey(config.apiKey())
                .modelName(config.model()).reasoningEffort(profile.effort()).customParameters(profile.extra())
                .returnThinking(profile.sendThinking()).sendThinking(profile.sendThinking(),"reasoning_content")
                .timeout(timeout).maxRetries(retries).build();
    }
    @Override public StreamingChatModel streaming(LlmRuntimeConfig config,ModelReasoningPolicy.Profile profile,HttpClientBuilder transport,Duration timeout) {
        return OpenAiStreamingChatModel.builder().httpClientBuilder(transport).baseUrl(config.baseUrl()).apiKey(config.apiKey())
                .modelName(config.model()).reasoningEffort(profile.effort()).customParameters(profile.extra())
                .returnThinking(profile.sendThinking()).sendThinking(profile.sendThinking(),"reasoning_content").timeout(timeout).build();
    }
}
