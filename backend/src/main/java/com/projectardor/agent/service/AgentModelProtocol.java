package com.projectardor.agent.service;

import java.time.Duration;
import com.projectardor.agent.ReasoningDepth;
import com.projectardor.llm.service.LlmConfigService.LlmRuntimeConfig;
import com.projectardor.llm.service.ModelProtocol;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;

/** Agent-only SDK adapter; business domains do not depend on these transport types. */
public interface AgentModelProtocol extends ModelProtocol {
    default ModelReasoningPolicy.Profile reasoning(LlmRuntimeConfig config,ReasoningDepth depth) {
        return ModelReasoningPolicy.resolve(config,depth);
    }
    ChatModel chat(LlmRuntimeConfig config,ModelReasoningPolicy.Profile reasoning,HttpClientBuilder transport,Duration timeout,int retries);
    StreamingChatModel streaming(LlmRuntimeConfig config,ModelReasoningPolicy.Profile reasoning,HttpClientBuilder transport,Duration timeout);
}
