package com.projectardor.agent.service;

import java.io.ByteArrayInputStream;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LeasedHttpClientBuilderTests {
    @Test void cancellationClosesResponseBodyEvenBeforeAnyModelToken() {
        AgentStreamLease lease = new AgentStreamLease();
        HttpClient transport = mock(HttpClient.class);
        HttpClientBuilder builder = mock(HttpClientBuilder.class);
        when(builder.build()).thenReturn(transport);
        TrackedInput input = new TrackedInput();
        ServerSentEventListener listener = mock(ServerSentEventListener.class);
        ServerSentEventParser parser = mock(ServerSentEventParser.class);
        doAnswer(call -> { ((ServerSentEventParser) call.getArgument(1)).parse(input, listener); return null; })
                .when(transport).execute(any(HttpRequest.class), any(ServerSentEventParser.class), eq(listener));
        HttpClient wrapped = new LeasedHttpClientBuilder(builder, lease).build();
        wrapped.execute(mock(HttpRequest.class), parser, listener);
        verify(parser).parse(input, listener);
        assertThat(input.closed).isFalse();
        lease.stop(() -> {});
        assertThat(input.closed).isTrue();
        assertThatThrownBy(() -> wrapped.execute(mock(HttpRequest.class), parser, listener)).isInstanceOf(CancellationException.class);
        verify(transport, times(1)).execute(any(HttpRequest.class), any(ServerSentEventParser.class), eq(listener));
    }
    @Test void lateHeadersAfterStopAreClosedWithoutEnteringTheParser() {
        AgentStreamLease lease = new AgentStreamLease();
        HttpClient transport = mock(HttpClient.class);
        HttpClientBuilder builder = mock(HttpClientBuilder.class);
        when(builder.build()).thenReturn(transport);
        AtomicReference<ServerSentEventParser> registered = new AtomicReference<>();
        doAnswer(call -> { registered.set(call.getArgument(1)); return null; }).when(transport)
                .execute(any(HttpRequest.class), any(ServerSentEventParser.class), any(ServerSentEventListener.class));
        ServerSentEventParser parser = mock(ServerSentEventParser.class);
        ServerSentEventListener listener = mock(ServerSentEventListener.class);
        new LeasedHttpClientBuilder(builder, lease).build().execute(mock(HttpRequest.class), parser, listener);
        lease.stop(() -> {});
        TrackedInput input = new TrackedInput();
        registered.get().parse(input, listener);
        assertThat(input.closed).isTrue();
        verifyNoInteractions(parser);
        verify(listener).onError(any(CancellationException.class));
    }
    private static class TrackedInput extends ByteArrayInputStream {
        boolean closed;
        TrackedInput() { super(new byte[0]); }
        @Override public void close() { closed = true; }
    }
}
