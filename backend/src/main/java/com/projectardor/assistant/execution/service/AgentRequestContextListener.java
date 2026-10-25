package com.projectardor.assistant.execution.service;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicLong;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;

/** Measures each actual request, including tool schemas and the current tool-result history.
 * Does not retain prompt bodies and does not depend on trace collection being enabled. */
final class AgentRequestContextListener implements ChatModelListener {
    private static final String CALL = AgentRequestContextListener.class.getName() + ".call";
    private final TokenCountEstimator estimator;
    private final Consumer<AgentContextUsage> observer;
    private final BooleanSupplier active;
    private final AgentContextUsage base;
    private final AtomicLong sequence = new AtomicLong();

    AgentRequestContextListener(TokenCountEstimator estimator, AgentContextUsage base,
            Consumer<AgentContextUsage> observer, BooleanSupplier active) {
        this.estimator = estimator;
        this.base = base;
        this.observer = observer;
        this.active = active;
    }

    @Override public synchronized void onRequest(ChatModelRequestContext context) {
        if (!active.getAsBoolean() || context.chatRequest() == null) return;
        long call = sequence.incrementAndGet();
        context.attributes().put(CALL, call);
        var request = context.chatRequest();
        long tokens = 0;
        for (var message : request.messages()) {
            tokens += estimator == null ? 4L + estimate(message.toString())
                    : estimator.estimateTokenCountInMessage(message);
        }
        if (request.toolSpecifications() != null) {
            for (var tool : request.toolSpecifications()) tokens += estimate(tool.toString());
        }
        if (request.responseFormat() != null) tokens += estimate(request.responseFormat().toString());
        publish(tokens);
    }

    @Override public synchronized void onResponse(ChatModelResponseContext context) {
        if (!active.getAsBoolean() || !Long.valueOf(sequence.get()).equals(context.attributes().get(CALL))) return;
        var response = context.chatResponse();
        var usage = response == null ? null : response.tokenUsage();
        // Provider input usage is per request, never the run's cumulative billing total.
        if (usage != null && usage.inputTokenCount() != null && usage.inputTokenCount() > 0)
            publish(usage.inputTokenCount().longValue());
    }

    private long estimate(String text) {
        return text == null ? 0 : estimator == null ? (text.length() + 1L) / 2
                : estimator.estimateTokenCountInText(text);
    }

    private void publish(long tokens) {
        observer.accept(new AgentContextUsage(base.state(), base.model(), tokens,
                base.compressionBudgetTokens(), base.modelWindowTokens(), base.summaryVersion(), base.compactedMessages()));
    }
}
