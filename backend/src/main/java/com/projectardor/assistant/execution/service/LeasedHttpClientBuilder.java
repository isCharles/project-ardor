package com.projectardor.assistant.execution.service;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import dev.langchain4j.http.client.sse.ServerSentEvent;
import dev.langchain4j.http.client.sse.ServerSentEventContext;

/** Capture the body as soon as headers arrive, even before a provider emits its first token. */
final class LeasedHttpClientBuilder implements HttpClientBuilder {
    private final HttpClientBuilder delegate;
    private final AgentStreamLease lease;
    LeasedHttpClientBuilder(HttpClientBuilder delegate, AgentStreamLease lease) { this.delegate = delegate; this.lease = lease; }
    @Override public Duration connectTimeout() { return delegate.connectTimeout(); }
    @Override public HttpClientBuilder connectTimeout(Duration timeout) { delegate.connectTimeout(timeout); return this; }
    @Override public Duration readTimeout() { return delegate.readTimeout(); }
    @Override public HttpClientBuilder readTimeout(Duration timeout) { delegate.readTimeout(timeout); return this; }
    @Override public HttpClient build() {
        HttpClient client = delegate.build();
        return new HttpClient() {
            @Override public SuccessfulHttpResponse execute(HttpRequest request) {
                requireActive();
                return client.execute(request);
            }
            @Override public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
                requireActive();
                var body = new java.util.concurrent.atomic.AtomicReference<java.io.InputStream>();
                var finished = new java.util.concurrent.atomic.AtomicBoolean();
                var guarded = guard(listener, body, finished);
                client.execute(request, (input, events) -> {
                    body.set(input);
                    if (finished.get()) {
                        try { input.close(); } catch (java.io.IOException ignored) { /* original error wins */ }
                        return;
                    }
                    boolean active = lease.attachTransportCancellation(() -> {
                        try { input.close(); }
                        catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
                    });
                    if (!active) { events.onError(new CancellationException("Agent run stopped")); return; }
                    parser.parse(input, events);
                }, guarded);
            }
            private void requireActive() { if (lease.stopped()) throw new CancellationException("Agent run stopped"); }
        };
    }

    /** Some SDK parsers swallow callback exceptions; report them before that boundary. */
    private static ServerSentEventListener guard(ServerSentEventListener listener,
            java.util.concurrent.atomic.AtomicReference<java.io.InputStream> body,
            java.util.concurrent.atomic.AtomicBoolean finished) {
        return new ServerSentEventListener() {
            private void forward(Runnable callback) {
                if (finished.get()) return;
                try { callback.run(); } catch (RuntimeException failure) { onError(failure); }
            }
            @Override public void onOpen(SuccessfulHttpResponse response) { forward(() -> listener.onOpen(response)); }
            @Override public void onEvent(ServerSentEvent event) { forward(() -> listener.onEvent(event)); }
            @Override public void onEvent(ServerSentEvent event, ServerSentEventContext context) {
                forward(() -> listener.onEvent(event, context));
            }
            @Override public void onClose() {
                forward(listener::onClose);
                finished.set(true);
            }
            @Override public void onError(Throwable failure) {
                if (!finished.compareAndSet(false, true)) return;
                try { listener.onError(failure); }
                finally {
                    java.io.InputStream input = body.get();
                    if (input != null) try { input.close(); } catch (java.io.IOException ignored) { /* original error wins */ }
                }
            }
        };
    }
}
