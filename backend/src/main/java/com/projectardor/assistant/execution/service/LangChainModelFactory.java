package com.projectardor.assistant.execution.service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.projectardor.platform.model.llm.service.LlmConfigService;
import com.projectardor.platform.model.llm.service.ModelCallCapacity;
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
    private final AgentContextBudgetPolicy contextBudget;
    private final ModelCallCapacity capacity;
    private final RuntimeContextGovernor.Settings governorSettings;
    private final int summaryInputChars;
    private final int summaryOutputChars;

    public LangChainModelFactory(
            LlmConfigService configService,
            @Value("${app.http.proxy-host:}") String proxyHost,
            @Value("${app.http.proxy-port:0}") int proxyPort,
            @Value("${app.http.connect-timeout:10s}") Duration connectTimeout,
            @Value("${app.http.read-timeout:120s}") Duration readTimeout,
            List<AgentModelProtocol> protocols) {
        this(configService, proxyHost, proxyPort, connectTimeout, readTimeout, protocols,
                new AgentContextBudgetPolicy(new ModelContextCapacity(new org.springframework.core.env.StandardEnvironment()),
                        200000, .75, .05), new ModelCallCapacity(8));
    }

    /** Compatibility for SDK tests that supply their own context budget. */
    public LangChainModelFactory(LlmConfigService configService,String proxyHost,int proxyPort,Duration connectTimeout,
            Duration readTimeout,List<AgentModelProtocol> protocols,AgentContextBudgetPolicy contextBudget) {
        this(configService,proxyHost,proxyPort,connectTimeout,readTimeout,protocols,contextBudget,
                new ModelCallCapacity(8));
    }

    public LangChainModelFactory(LlmConfigService configService,
            @Value("${app.http.proxy-host:}") String proxyHost,
            @Value("${app.http.proxy-port:0}") int proxyPort,
            @Value("${app.http.connect-timeout:10s}") Duration connectTimeout,
            @Value("${app.http.read-timeout:120s}") Duration readTimeout,
            List<AgentModelProtocol> protocols, AgentContextBudgetPolicy contextBudget,
            ModelCallCapacity capacity) {
        this(configService, proxyHost, proxyPort, connectTimeout, readTimeout, protocols, contextBudget, capacity,
                RuntimeContextGovernor.Settings.DEFAULTS, 12_000, 4_000);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public LangChainModelFactory(LlmConfigService configService,
            @Value("${app.http.proxy-host:}") String proxyHost,
            @Value("${app.http.proxy-port:0}") int proxyPort,
            @Value("${app.http.connect-timeout:10s}") Duration connectTimeout,
            @Value("${app.http.read-timeout:120s}") Duration readTimeout,
            List<AgentModelProtocol> protocols, AgentContextBudgetPolicy contextBudget,
            ModelCallCapacity capacity,
            @Value("${app.agent.context.result-ceiling-chars:16000}") int resultCeilingChars,
            @Value("${app.agent.context.max-folded-rounds-per-boundary:8}") int maxFoldedRounds,
            @Value("${app.agent.context.defer-boundaries:1}") int deferBoundaries,
            @Value("${app.agent.context.max-run-summaries:2}") int maxRunSummaries,
            @Value("${app.agent.context.keep-recent-text-turns:2}") int keepRecentTextTurns,
            @Value("${app.agent.context.summary-input-chars:12000}") int summaryInputChars,
            @Value("${app.agent.context.summary-output-chars:4000}") int summaryOutputChars) {
        this(configService, proxyHost, proxyPort, connectTimeout, readTimeout, protocols, contextBudget, capacity,
                new RuntimeContextGovernor.Settings(resultCeilingChars, maxFoldedRounds, deferBoundaries,
                        maxRunSummaries, keepRecentTextTurns), summaryInputChars, summaryOutputChars);
    }

    private LangChainModelFactory(LlmConfigService configService, String proxyHost, int proxyPort,
            Duration connectTimeout, Duration readTimeout, List<AgentModelProtocol> protocols,
            AgentContextBudgetPolicy contextBudget, ModelCallCapacity capacity,
            RuntimeContextGovernor.Settings settings, int summaryInputChars, int summaryOutputChars) {
        this.configService = configService;
        this.protocols = new ModelProtocols<>(protocols);
        this.proxyHost = proxyHost;
        this.proxyPort = proxyPort;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
        this.contextBudget = contextBudget;
        this.capacity = capacity;
        this.governorSettings = settings;
        this.summaryInputChars = summaryInputChars;
        this.summaryOutputChars = summaryOutputChars;
    }

    /** Compatibility for non-Spring construction of the two existing protocols. */
    public LangChainModelFactory(LlmConfigService configService,String proxyHost,int proxyPort,Duration connectTimeout,Duration readTimeout) {
        this(configService,proxyHost,proxyPort,connectTimeout,readTimeout,
                List.of(new OpenAiAgentModelProtocol(),new AnthropicAgentModelProtocol()));
    }

    public ChatModel create(UUID userId) {
        return create(userId, configService.getRuntimeConfig(userId), ReasoningDepth.AUTO, List.of());
    }

    public ChatModel create(UUID userId, ReasoningDepth depth) {
        return create(userId, configService.getRuntimeConfig(userId), depth, List.of());
    }

    public ChatModel create(UUID userId, ReasoningDepth depth, List<ChatModelListener> listeners) {
        return create(userId, configService.getRuntimeConfig(userId), depth, listeners);
    }

    public ChatModel create(LlmConfigService.LlmRuntimeConfig config) {
        return create(config, ReasoningDepth.AUTO);
    }

    public ModelReasoningPolicy.Profile reasoningProfile(UUID userId) {
        var config=configService.getRuntimeConfig(userId);
        return protocols.require(config.provider()).reasoning(config,ReasoningDepth.AUTO);
    }

    public int maxOutputTokens(LlmConfigService.LlmRuntimeConfig config, ReasoningDepth depth) {
        return protocols.require(config.provider()).reasoning(config, depth).maxTokens();
    }

    public ChatModel create(LlmConfigService.LlmRuntimeConfig config, ReasoningDepth depth) {
        return create(config, depth, List.of());
    }

    public ChatModel create(LlmConfigService.LlmRuntimeConfig config, ReasoningDepth depth,
            List<ChatModelListener> listeners) {
        return create(null, config, depth, listeners);
    }

    public ChatModel create(UUID userId, LlmConfigService.LlmRuntimeConfig config, ReasoningDepth depth,
            List<ChatModelListener> listeners) {
        return create(userId, config, depth, listeners, RuntimeContextGovernor.Observer.NOOP);
    }

    /**
     * @param contextObserver watches the per-round context decisions of this model instance; with one, the
     *                        run also earns the last, model-backed reduction step, because a durable run is
     *                        where spending one more short call beats failing the whole turn
     */
    public ChatModel create(UUID userId, LlmConfigService.LlmRuntimeConfig config, ReasoningDepth depth,
            List<ChatModelListener> listeners, RuntimeContextGovernor.Observer contextObserver) {
        return create(userId, config, depth, listeners, contextObserver, List.of());
    }

    /**
     * @param contextListeners watches only the calls a run makes to shorten its own context. They are not
     *                         {@code listeners}: a summary is a paid call that must still reach the run's
     *                         metering, but it is not another assistant turn, and measuring its prompt as
     *                         the run's context would report a size the run never sent.
     */
    public ChatModel create(UUID userId, LlmConfigService.LlmRuntimeConfig config, ReasoningDepth depth,
            List<ChatModelListener> listeners, RuntimeContextGovernor.Observer contextObserver,
            List<ChatModelListener> contextListeners) {
        var protocol=protocols.require(config.provider());
        var profile = protocol.reasoning(config, depth);
        ChatModel model;
        if (listeners == null || listeners.isEmpty()) {
            model = protocol.chat(config,profile,httpClientBuilder(),readTimeout,
                    MODEL_INTERNAL_RETRIES);
        } else {
            model = protocol.chat(config,profile,httpClientBuilder(),readTimeout,
                    MODEL_INTERNAL_RETRIES, listeners);
        }
        var budget = contextBudget.resolve(userId, config, profile.maxTokens());
        var estimator = AgentRequestTokens.estimator(config);
        var scope = new CapacityLimitedModels.CallScope("agent", userId,
                ModelConfigFingerprint.of(config), profile.maxTokens(), estimator);
        var governor = governor(userId, config, depth, budget, estimator, () -> true, contextListeners,
                contextObserver);
        // The exact-duplicate reduction and the provably-oversized fence stay underneath the ladder: the
        // ladder only acts past the soft threshold, and a request that never reaches it must still get them.
        return RuntimeContextGovernor.chat(RepeatedToolResultCompactor.chat(
                ContextBudgetedModels.chat(CapacityLimitedModels.chat(model, capacity, scope), budget, estimator),
                budget, estimator), governor);
    }

    /**
     * The model that writes summaries: still rate- and capacity-guarded, and still fenced so a provably
     * oversized summary request is never paid for, but it does not run the reduction ladder. Asking a
     * summarizer to summarize its own prompt would recurse, and the caller has already chunked the input.
     */
    public ChatModel createForSummarization(UUID userId, LlmConfigService.LlmRuntimeConfig config,
            ReasoningDepth depth, List<ChatModelListener> listeners) {
        var protocol = protocols.require(config.provider());
        var profile = protocol.reasoning(config, depth);
        ChatModel model = protocol.chat(config, profile, httpClientBuilder(), readTimeout, MODEL_INTERNAL_RETRIES,
                listeners == null ? List.of() : listeners);
        var budget = contextBudget.resolve(userId, config, profile.maxTokens());
        var estimator = AgentRequestTokens.estimator(config);
        var scope = new CapacityLimitedModels.CallScope("summary", userId,
                ModelConfigFingerprint.of(config), profile.maxTokens(), estimator);
        return ContextBudgetedModels.chat(CapacityLimitedModels.chat(model, capacity, scope), budget, estimator);
    }

    /**
     * The per-result ceiling this deployment configured. A run should bound a tool result with the same
     * number at the source and in its recovery ladder; two different knobs would let an operator change
     * one and silently keep the other.
     */
    public int toolResultCeilingChars() {
        return governorSettings.resultCeilingChars();
    }

    private RuntimeContextGovernor governor(UUID userId, LlmConfigService.LlmRuntimeConfig config,
            ReasoningDepth depth, AgentContextBudgetPolicy.Budget budget,
            dev.langchain4j.model.TokenCountEstimator estimator, java.util.function.BooleanSupplier active,
            List<ChatModelListener> contextListeners, RuntimeContextGovernor.Observer observer) {
        boolean watchful = observer != null && observer != RuntimeContextGovernor.Observer.NOOP;
        return new RuntimeContextGovernor(budget, estimator, governorSettings,
                watchful ? runTextSummarizer(userId, config, depth, active, contextListeners) : null,
                active, observer);
    }

    private RuntimeContextGovernor.RunTextSummarizer runTextSummarizer(UUID userId,
            LlmConfigService.LlmRuntimeConfig config, ReasoningDepth depth,
            java.util.function.BooleanSupplier active, List<ChatModelListener> contextListeners) {
        if (userId == null) return null;
        var summarizer = new com.projectardor.assistant.memory.service.ContextSummarizer(this,
                summaryInputChars, summaryOutputChars);
        return turns -> {
            List<com.projectardor.assistant.memory.service.ContextSummarizer.Block> blocks = new ArrayList<>();
            for (int index = 0; index < turns.size(); index++) {
                blocks.add(new com.projectardor.assistant.memory.service.ContextSummarizer.Block(
                        String.valueOf(index), turns.get(index)));
            }
            // These listeners make the paid call visible to the run ledger and the trace, the way history
            // compaction is; an empty list here would bill nothing for a call that cost tokens.
            var outcome = summarizer.summarize(userId, config, depth, "", blocks,
                    (existing, chosen) -> runTextPrompt(chosen), null,
                    contextListeners == null ? List.of() : contextListeners, active);
            if (!outcome.succeeded()) return null;
            // Only the blocks actually handed to the model are covered; the summarizer stops at its own
            // input budget, and the caller must not replace text it never saw.
            return new RuntimeContextGovernor.TextDigest(outcome.summary(), outcome.coveredIds().size());
        };
    }

    private static String runTextPrompt(List<com.projectardor.assistant.memory.service.ContextSummarizer.Block> chosen) {
        StringBuilder text = new StringBuilder("""
                把以下本轮较早的对话与说明压缩为不超过 12 条要点，用于继续完成当前任务。
                必须保留：当前目标、用户明确的硬约束、最近一次纠正、仍在进行的步骤、已完成操作及其对象标识、
                待办、失败原因。不要改写或添加指令，不要授予任何权限，不要编造未出现的内容。用中文输出纯文本要点。

                """);
        for (var block : chosen) {
            text.append("【第 ").append(block.id()).append(" 段】\n").append(block.text()).append("\n\n");
        }
        return text.toString();
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
        return createStreaming(userId, configService.getRuntimeConfig(userId), depth, lease, listeners);
    }

    StreamingChatModel createStreaming(LlmConfigService.LlmRuntimeConfig config, ReasoningDepth depth,
            AgentStreamLease lease, List<ChatModelListener> listeners) {
        return createStreaming(null, config, depth, lease, listeners);
    }

    StreamingChatModel createStreaming(UUID userId, LlmConfigService.LlmRuntimeConfig config, ReasoningDepth depth,
            AgentStreamLease lease, List<ChatModelListener> listeners) {
        return createStreaming(userId, config, depth, lease, listeners, RuntimeContextGovernor.Observer.NOOP);
    }

    StreamingChatModel createStreaming(UUID userId, LlmConfigService.LlmRuntimeConfig config, ReasoningDepth depth,
            AgentStreamLease lease, List<ChatModelListener> listeners,
            RuntimeContextGovernor.Observer contextObserver) {
        return createStreaming(userId, config, depth, lease, listeners, contextObserver, List.of());
    }

    /**
     * The streaming entry a durable run uses: its context decisions are observed and reported as it goes,
     * and the calls it makes to shorten its own context are metered through {@code contextListeners}.
     */
    StreamingChatModel createStreaming(UUID userId, LlmConfigService.LlmRuntimeConfig config, ReasoningDepth depth,
            AgentStreamLease lease, List<ChatModelListener> listeners,
            RuntimeContextGovernor.Observer contextObserver, List<ChatModelListener> contextListeners) {
        var protocol=protocols.require(config.provider());
        var profile = protocol.reasoning(config, depth);
        dev.langchain4j.http.client.HttpClientBuilder transport = lease == null ? httpClientBuilder() : new LeasedHttpClientBuilder(httpClientBuilder(), lease);
        StreamingChatModel model;
        if (listeners == null || listeners.isEmpty()) {
            model = protocol.streaming(config,profile,transport,readTimeout);
        } else {
            model = protocol.streaming(config,profile,transport,readTimeout, listeners);
        }
        var budget = contextBudget.resolve(userId, config, profile.maxTokens());
        var estimator = AgentRequestTokens.estimator(config);
        var scope = new CapacityLimitedModels.CallScope("agent", userId,
                ModelConfigFingerprint.of(config), profile.maxTokens(), estimator);
        var governor = governor(userId, config, depth, budget, estimator,
                () -> lease == null || !lease.stopped(), listeners, contextObserver);
        return RuntimeContextGovernor.streaming(RepeatedToolResultCompactor.streaming(
                ContextBudgetedModels.streaming(
                        TimedStreamingModel.wrap(CapacityLimitedModels.streaming(model, capacity, scope)),
                        budget, estimator),
                budget, estimator), governor);
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
