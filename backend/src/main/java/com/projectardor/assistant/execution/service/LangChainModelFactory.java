package com.projectardor.assistant.execution.service;

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

import com.projectardor.platform.model.llm.service.LlmConfigService;
import com.projectardor.platform.model.llm.service.ModelProtocols;
import com.projectardor.assistant.ReasoningDepth;

import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import com.projectardor.assistant.execution.service.AgentStreamLease;
import com.projectardor.assistant.execution.service.LeasedHttpClientBuilder;

@Component
public class LangChainModelFactory {

    // The browser owns the visible retry loop so it can report the exact attempt to the user.
    private static final int MODEL_INTERNAL_RETRIES = 0;

    private final LlmConfigService configService;
    private final ModelProtocols<AgentModelProtocol> protocols;
    private final String proxyHost;
    private final int proxyPort;
    private final Duration connectTimeout;
    private final Duration readTimeout;

    @org.springframework.beans.factory.annotation.Autowired
    public LangChainModelFactory(
            LlmConfigService configService,
            @Value("${app.http.proxy-host:}") String proxyHost,
            @Value("${app.http.proxy-port:0}") int proxyPort,
            @Value("${app.http.connect-timeout:10s}") Duration connectTimeout,
            @Value("${app.http.read-timeout:120s}") Duration readTimeout,
            List<AgentModelProtocol> protocols) {
        this.configService = configService;
        this.protocols = new ModelProtocols<>(protocols);
        this.proxyHost = proxyHost;
        this.proxyPort = proxyPort;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    /** Compatibility for non-Spring construction of the two existing protocols. */
    public LangChainModelFactory(LlmConfigService configService,String proxyHost,int proxyPort,Duration connectTimeout,Duration readTimeout) {
        this(configService,proxyHost,proxyPort,connectTimeout,readTimeout,
                List.of(new OpenAiAgentModelProtocol(),new AnthropicAgentModelProtocol()));
    }

    public ChatModel create(UUID userId) {
        return create(configService.getRuntimeConfig(userId));
    }

    public ChatModel create(UUID userId, ReasoningDepth depth) {
        return create(configService.getRuntimeConfig(userId), depth);
    }

    public ChatModel create(UUID userId, ReasoningDepth depth, List<ChatModelListener> listeners) {
        return create(configService.getRuntimeConfig(userId), depth, listeners);
    }

    public ChatModel create(LlmConfigService.LlmRuntimeConfig config) {
        return create(config, ReasoningDepth.AUTO);
    }

    public ModelReasoningPolicy.Profile reasoningProfile(UUID userId) {
        var config=configService.getRuntimeConfig(userId);
        return protocols.require(config.provider()).reasoning(config,ReasoningDepth.AUTO);
    }

    public ChatModel create(LlmConfigService.LlmRuntimeConfig config, ReasoningDepth depth) {
        return create(config, depth, List.of());
    }

    public ChatModel create(LlmConfigService.LlmRuntimeConfig config, ReasoningDepth depth,
            List<ChatModelListener> listeners) {
        var protocol=protocols.require(config.provider());
        if (listeners == null || listeners.isEmpty()) {
            return protocol.chat(config,protocol.reasoning(config,depth),httpClientBuilder(),readTimeout,
                    MODEL_INTERNAL_RETRIES);
        }
        return protocol.chat(config,protocol.reasoning(config,depth),httpClientBuilder(),readTimeout,
                MODEL_INTERNAL_RETRIES, listeners);
    }

    public StreamingChatModel createStreaming(UUID userId) {
        return createStreaming(userId, ReasoningDepth.AUTO);
    }

    public StreamingChatModel createStreaming(UUID userId, ReasoningDepth depth) {
        return createStreaming(userId, depth, null);
    }
    StreamingChatModel createStreaming(UUID userId, ReasoningDepth depth, AgentStreamLease lease) {
        return createStreaming(userId, depth, lease, List.of());
    }
    StreamingChatModel createStreaming(UUID userId, ReasoningDepth depth, AgentStreamLease lease,
            List<ChatModelListener> listeners) {
        LlmConfigService.LlmRuntimeConfig config = configService.getRuntimeConfig(userId);
        var protocol=protocols.require(config.provider());
        dev.langchain4j.http.client.HttpClientBuilder transport = lease == null ? httpClientBuilder() : new LeasedHttpClientBuilder(httpClientBuilder(), lease);
        if (listeners == null || listeners.isEmpty()) {
            return protocol.streaming(config,protocol.reasoning(config,depth),transport,readTimeout);
        }
        return protocol.streaming(config,protocol.reasoning(config,depth),transport,readTimeout, listeners);
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
