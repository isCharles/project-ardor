package com.projectardor.assistant.execution.service;

import java.time.Duration;
import java.util.List;
import com.projectardor.assistant.ReasoningDepth;
import com.projectardor.platform.model.llm.service.LlmConfigService.LlmRuntimeConfig;
import com.projectardor.platform.model.llm.service.ModelProtocol;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;

/** Agent-only SDK adapter; business domains do not depend on these transport types. */
public interface AgentModelProtocol extends ModelProtocol {
    default ModelReasoningPolicy.Profile reasoning(LlmRuntimeConfig config,ReasoningDepth depth) {
        return ModelReasoningPolicy.resolve(config,depth);
    }
    ChatModel chat(LlmRuntimeConfig config,ModelReasoningPolicy.Profile reasoning,HttpClientBuilder transport,Duration timeout,int retries);
    StreamingChatModel streaming(LlmRuntimeConfig config,ModelReasoningPolicy.Profile reasoning,HttpClientBuilder transport,Duration timeout);

    /** Optional listener-aware variant. Protocols that support native listeners should override it. */
    default ChatModel chat(LlmRuntimeConfig config, ModelReasoningPolicy.Profile reasoning,
            HttpClientBuilder transport, Duration timeout, int retries, List<ChatModelListener> listeners) {
        return chat(config, reasoning, transport, timeout, retries);
    }

    /** Optional listener-aware variant for streaming; callbacks stay attached to the explicit run. */
    default StreamingChatModel streaming(LlmRuntimeConfig config, ModelReasoningPolicy.Profile reasoning,
            HttpClientBuilder transport, Duration timeout, List<ChatModelListener> listeners) {
        return streaming(config, reasoning, transport, timeout);
    }
}
