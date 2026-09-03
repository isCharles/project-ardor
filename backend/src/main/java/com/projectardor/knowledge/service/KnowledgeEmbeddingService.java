package com.projectardor.knowledge.service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.projectardor.integrations.domain.AuxiliaryServiceType;
import com.projectardor.integrations.service.AuxiliaryApiConfigService;
import com.projectardor.integrations.service.AuxiliaryApiConfigService.AuxiliaryRuntimeConfig;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.output.Response;

/**
 * Turns text into vectors using the embedding endpoint the current user configured
 * under Settings -> 未来能力 -> 向量模型 (auxiliary_api_configs, service_type = 'EMBEDDING').
 *
 * <p>Every call resolves the caller's own credentials; there is no shared or server-wide
 * embedding key, which keeps embeddings on the same per-user isolation model as the rest
 * of the app.
 */
@Service
public class KnowledgeEmbeddingService {

    /** Keeps a single request well inside typical provider batch limits. */
    private static final int MAX_BATCH = 32;

    private final AuxiliaryApiConfigService auxiliaryConfigService;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final String proxyHost;
    private final int proxyPort;

    public KnowledgeEmbeddingService(
            AuxiliaryApiConfigService auxiliaryConfigService,
            @Value("${app.http.proxy-host:}") String proxyHost,
            @Value("${app.http.proxy-port:0}") int proxyPort,
            @Value("${app.http.connect-timeout:10s}") Duration connectTimeout,
            @Value("${app.http.read-timeout:120s}") Duration readTimeout) {
        this.auxiliaryConfigService = auxiliaryConfigService;
        this.proxyHost = proxyHost;
        this.proxyPort = proxyPort;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    public boolean isConfigured(UUID userId) {
        return auxiliaryConfigService.runtimeConfig(userId, AuxiliaryServiceType.EMBEDDING).isPresent();
    }

    /**
     * Embeds one query. Returns {@code null} when the user has not configured an
     * embedding model, so callers can degrade to keyword-only retrieval instead of failing.
     */
    public EmbeddingVector embedQuery(UUID userId, String text) {
        return auxiliaryConfigService.runtimeConfig(userId, AuxiliaryServiceType.EMBEDDING)
                .map(config -> embedAll(config, List.of(text)).get(0))
                .orElse(null);
    }

    /**
     * Embeds a batch of chunk texts. Requires a configured model; callers that may run
     * without one should check {@link #isConfigured(UUID)} first.
     */
    public List<EmbeddingVector> embedDocuments(UUID userId, List<String> texts) {
        AuxiliaryRuntimeConfig config = auxiliaryConfigService
                .runtimeConfig(userId, AuxiliaryServiceType.EMBEDDING)
                .orElseThrow(() -> new IllegalStateException(
                        "请先在 设置 → 未来能力 → 向量模型 配置一个 Embedding 服务，语义检索才能工作"));
        return embedAll(config, texts);
    }

    public String modelName(UUID userId) {
        return auxiliaryConfigService.runtimeConfig(userId, AuxiliaryServiceType.EMBEDDING)
                .map(AuxiliaryRuntimeConfig::model)
                .orElse(null);
    }

    private List<EmbeddingVector> embedAll(AuxiliaryRuntimeConfig config, List<String> texts) {
        OpenAiEmbeddingModel model = OpenAiEmbeddingModel.builder()
                .httpClientBuilder(httpClientBuilder())
                .baseUrl(config.baseUrl())
                .apiKey(config.apiKey())
                .modelName(config.model())
                .maxRetries(1)
                .build();

        List<EmbeddingVector> vectors = new java.util.ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += MAX_BATCH) {
            List<TextSegment> batch = texts.subList(start, Math.min(texts.size(), start + MAX_BATCH))
                    .stream().map(TextSegment::from).toList();
            Response<List<dev.langchain4j.data.embedding.Embedding>> response = model.embedAll(batch);
            for (dev.langchain4j.data.embedding.Embedding embedding : response.content()) {
                vectors.add(new EmbeddingVector(embedding.vector(), config.model()));
            }
        }
        if (vectors.size() != texts.size()) {
            throw new IllegalStateException("Embedding 服务返回的向量数量与输入不一致");
        }
        return vectors;
    }

    /**
     * Mirrors the proxy handling in {@code LangChainModelFactory}: when a host proxy is
     * configured, external embedding endpoints go through it, while loopback, private and
     * {@code *.internal} hosts are reached directly.
     */
    private JdkHttpClientBuilder httpClientBuilder() {
        java.net.http.HttpClient.Builder client =
                java.net.http.HttpClient.newBuilder().connectTimeout(connectTimeout);
        if (proxyHost != null && !proxyHost.isBlank() && proxyPort > 0) {
            java.net.Proxy proxy = new java.net.Proxy(
                    java.net.Proxy.Type.HTTP,
                    new java.net.InetSocketAddress(proxyHost.strip(), proxyPort));
            client.proxy(new java.net.ProxySelector() {
                @Override
                public List<java.net.Proxy> select(java.net.URI uri) {
                    return bypassProxy(uri.getHost()) ? List.of(java.net.Proxy.NO_PROXY) : List.of(proxy);
                }

                @Override
                public void connectFailed(java.net.URI uri, java.net.SocketAddress address, java.io.IOException e) {
                    // The HTTP client surfaces the original failure to the caller.
                }
            });
        }
        return new JdkHttpClientBuilder()
                .httpClientBuilder(client)
                .connectTimeout(connectTimeout)
                .readTimeout(readTimeout);
    }

    private static boolean bypassProxy(String host) {
        if (host == null || host.isBlank()) return false;
        String normalized = host.toLowerCase(java.util.Locale.ROOT);
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

    /** A single embedding plus the model that produced it. */
    public record EmbeddingVector(float[] values, String model) {

        public int dimension() {
            return values.length;
        }

        /**
         * Renders the vector in pgvector's text input format. pgvector parses
         * {@code '[1,2,3]'::vector}, which lets us store and query vectors over plain
         * JDBC without pulling in a pgvector Java client.
         */
        public String toPgVectorLiteral() {
            StringBuilder builder = new StringBuilder(values.length * 12 + 2);
            builder.append('[');
            for (int index = 0; index < values.length; index++) {
                if (index > 0) {
                    builder.append(',');
                }
                builder.append(values[index]);
            }
            return builder.append(']').toString();
        }
    }
}
