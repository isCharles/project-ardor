package com.projectardor.agent.service;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;

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
                client.execute(request, (input, events) -> {
                    boolean active = lease.attachTransportCancellation(() -> {
                        try { input.close(); }
                        catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
                    });
                    if (!active) { events.onError(new CancellationException("Agent run stopped")); return; }
                    parser.parse(input, events);
                }, listener);
            }
            private void requireActive() { if (lease.stopped()) throw new CancellationException("Agent run stopped"); }
        };
    }
}
