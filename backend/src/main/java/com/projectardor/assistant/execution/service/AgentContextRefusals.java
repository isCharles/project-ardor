package com.projectardor.assistant.execution.service;

import java.util.List;
import java.util.Locale;

/**
 * Whether a provider refused a request because of its length, and nothing else.
 *
 * <p>Only a refusal the provider actually describes as too long is worth reducing and retrying. A
 * timeout, an auth failure or a rate limit looks identical from here — a 400-shaped failure — but
 * trimming context would throw away work for no reason and hide the real cause. So both gates have to
 * agree: a request-level status code, and wording that names length, tokens or the context window.</p>
 *
 * <p>The upstream body is inspected and then dropped; it is never copied into a user-facing error.</p>
 */
public final class AgentContextRefusals {
    private static final List<String> MARKERS = List.of(
            "context length", "context_length", "context window", "maximum context", "too many tokens",
            "input too long", "prompt is too long", "prompt too long", "exceeds the model", "token limit",
            "reduce the length", "input length", "上下文长度", "超出上下文", "输入过长", "token 数超过");
    private static final int MAX_STATUS = 499;

    private AgentContextRefusals() {}

    public static boolean isLengthRefusal(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            Integer status = status(current);
            String text = message(current);
            if (status != null && status >= 400 && status < MAX_STATUS && mentionsLength(text)) return true;
        }
        return false;
    }

    private static boolean mentionsLength(String text) {
        if (text == null || text.isBlank()) return false;
        String lowered = text.toLowerCase(Locale.ROOT);
        return MARKERS.stream().anyMatch(lowered::contains);
    }

    private static Integer status(Throwable failure) {
        // Only the agent transport is retried here, and it reaches providers through LangChain4j; the
        // platform JSON gateway has its own classifier and never asks this question.
        if (failure instanceof dev.langchain4j.exception.HttpException http) return http.statusCode();
        return null;
    }

    private static String message(Throwable failure) {
        // A provider body may be the only place the reason appears, and it is the cause's own message.
        String message = failure.getMessage();
        return message == null || message.isBlank() ? null : message;
    }
}
