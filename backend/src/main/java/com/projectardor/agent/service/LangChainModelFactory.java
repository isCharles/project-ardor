package com.projectardor.agent.service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.projectardor.llm.service.LlmConfigService;

import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;

@Component
public class LangChainModelFactory {

    // The browser owns the visible retry loop so it can report the exact attempt to the user.
    private static final int MODEL_INTERNAL_RETRIES = 0;

    private final LlmConfigService configService;
    private final String proxyHost;
    private final int proxyPort;
    private final Duration connectTimeout;
    private final Duration readTimeout;

    public LangChainModelFactory(
            LlmConfigService configService,
            @Value("${app.http.proxy-host:}") String proxyHost,
            @Value("${app.http.proxy-port:0}") int proxyPort,
            @Value("${app.http.connect-timeout:10s}") Duration connectTimeout,
            @Value("${app.http.read-timeout:120s}") Duration readTimeout) {
        this.configService = configService;
        this.proxyHost = proxyHost;
        this.proxyPort = proxyPort;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    public ChatModel create(UUID userId) {
        return create(configService.getRuntimeConfig(userId));
    }

    public ChatModel create(LlmConfigService.LlmRuntimeConfig config) {
        return switch (config.provider()) {
            case OPENAI_COMPATIBLE -> OpenAiChatModel.builder()
                    .httpClientBuilder(httpClientBuilder())
                    .baseUrl(config.baseUrl())
                    .apiKey(config.apiKey())
                    .modelName(config.model())
                    .timeout(readTimeout)
                    .maxRetries(MODEL_INTERNAL_RETRIES)
                    .build();
            case ANTHROPIC_COMPATIBLE -> AnthropicChatModel.builder()
                    .httpClientBuilder(httpClientBuilder())
                    .baseUrl(config.baseUrl())
                    .apiKey(config.apiKey())
                    .modelName(config.model())
                    .maxTokens(4096)
                    .timeout(readTimeout)
                    .maxRetries(MODEL_INTERNAL_RETRIES)
                    .build();
        };
    }

    public StreamingChatModel createStreaming(UUID userId) {
        LlmConfigService.LlmRuntimeConfig config = configService.getRuntimeConfig(userId);
        return switch (config.provider()) {
            case OPENAI_COMPATIBLE -> OpenAiStreamingChatModel.builder()
                    .httpClientBuilder(httpClientBuilder()).baseUrl(config.baseUrl()).apiKey(config.apiKey())
                    .modelName(config.model()).timeout(readTimeout).build();
            case ANTHROPIC_COMPATIBLE -> AnthropicStreamingChatModel.builder()
                    .httpClientBuilder(httpClientBuilder()).baseUrl(config.baseUrl()).apiKey(config.apiKey())
                    .modelName(config.model()).maxTokens(4096).timeout(readTimeout).build();
        };
    }

    private JdkHttpClientBuilder httpClientBuilder() {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(connectTimeout);
        if (proxyHost != null && !proxyHost.isBlank() && proxyPort > 0) {
            builder.proxy(new ArdorProxySelector(proxyHost.strip(), proxyPort));
        }
        return new JdkHttpClientBuilder()
                .httpClientBuilder(builder)
                .connectTimeout(connectTimeout)
                .readTimeout(readTimeout);
    }

    private static final class ArdorProxySelector extends ProxySelector {
        private final Proxy proxy;

        private ArdorProxySelector(String host, int port) {
            this.proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port));
        }

        @Override
        public List<Proxy> select(URI uri) {
            return bypassProxy(uri.getHost()) ? List.of(Proxy.NO_PROXY) : List.of(proxy);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress address, IOException exception) {
            // The HTTP client reports the original failure to the caller.
        }

        private boolean bypassProxy(String host) {
            if (host == null || host.isBlank()) return false;
            String normalized = host.toLowerCase(Locale.ROOT);
            if (normalized.equals("localhost") || normalized.equals("127.0.0.1")
                    || normalized.equals("::1") || normalized.endsWith(".internal")
                    || !normalized.contains(".")) return true;
            if (normalized.startsWith("10.") || normalized.startsWith("192.168.")) return true;
            if (!normalized.startsWith("172.")) return false;
            String[] segments = normalized.split("\\.");
            if (segments.length < 2) return false;
            try {
                int second = Integer.parseInt(segments[1]);
                return second >= 16 && second <= 31;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
    }
}
