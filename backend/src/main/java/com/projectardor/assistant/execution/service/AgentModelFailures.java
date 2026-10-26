package com.projectardor.assistant.execution.service;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import com.projectardor.platform.model.llm.service.LlmCallException;
import com.projectardor.platform.model.llm.service.LlmFailureClassifier;
import dev.langchain4j.exception.*;

/** SDK exceptions stay at the assistant boundary; classification belongs to the model platform. */
final class AgentModelFailures {
    private AgentModelFailures() {}

    static Throwable classify(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
            if (current instanceof AgentContextLimitException) return current;
            if (current instanceof LlmCallException) return current;
            if (current instanceof HttpException http) return LlmFailureClassifier.http(http.statusCode(), failure);
            if (current instanceof AuthenticationException)
                return LlmFailureClassifier.http(401, failure);
            if (current instanceof InvalidRequestException) return LlmFailureClassifier.http(400, failure);
            if (current instanceof ModelNotFoundException) return LlmFailureClassifier.http(404, failure);
            if (current instanceof RateLimitException) return LlmFailureClassifier.http(429, failure);
            if (current instanceof InternalServerException) return LlmFailureClassifier.http(500, failure);
            if (current instanceof UnresolvedModelServerException) return LlmFailureClassifier.dns(failure);
            if (current instanceof dev.langchain4j.exception.TimeoutException)
                return upstreamTimeout(failure);
        }
        LlmCallException payload = LlmFailureClassifier.payload(failure);
        if (payload != null) return payload;
        LlmCallException transport = LlmFailureClassifier.transport(failure);
        if (transport == null) return failure;
        if ("LLM_TIMEOUT".equals(transport.getCode()))
            return upstreamTimeout(failure);
        return transport;
    }

    private static LlmCallException upstreamTimeout(Throwable failure) {
        return new LlmCallException("AGENT_UPSTREAM_TIMEOUT", "上游连接或读取超时，本轮已停止；无法确认远端生成状态，不会自动重试。", false, failure);
    }
}
