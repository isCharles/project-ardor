package com.projectardor.speech.realtime;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.projectardor.integrations.service.AuxiliaryApiConfigService.AuxiliaryRuntimeConfig;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class DashScopeRealtimeSpeechGateway {

    private final ObjectMapper objectMapper;
    private final String proxyHost;
    private final int proxyPort;
    private final Duration connectTimeout;
    private final String asrModel;
    private final String ttsModel;
    private final String voice;
    private final int vadSilenceDuration;

    public DashScopeRealtimeSpeechGateway(
            ObjectMapper objectMapper,
            @Value("${app.http.proxy-host:}") String proxyHost,
            @Value("${app.http.proxy-port:0}") int proxyPort,
            @Value("${app.http.connect-timeout:10s}") Duration connectTimeout,
            @Value("${app.speech.realtime-asr-model:qwen3-asr-flash-realtime}") String asrModel,
            @Value("${app.speech.realtime-tts-model:qwen3-tts-flash-realtime}") String ttsModel,
            @Value("${app.speech.dashscope-voice:Cherry}") String voice,
            @Value("${app.speech.vad-silence-duration:800}") int vadSilenceDuration) {
        this.objectMapper = objectMapper;
        this.proxyHost = proxyHost == null ? "" : proxyHost.strip();
        this.proxyPort = proxyPort;
        this.connectTimeout = connectTimeout;
        this.asrModel = asrModel;
        this.ttsModel = ttsModel;
        this.voice = voice;
        this.vadSilenceDuration = Math.max(200, Math.min(6_000, vadSilenceDuration));
    }

    public RealtimeSession openAsr(
            AuxiliaryRuntimeConfig config,
            Consumer<RealtimeSpeechEvent> events) {
        requireDashScope(config);
        UpstreamSession session = new UpstreamSession(events, Protocol.ASR, null);
        session.connect(realtimeUri(config.baseUrl(), realtimeModel(config.model(), asrModel, "asr")), config.apiKey());
        return session;
    }

    public RealtimeSession openTts(
            AuxiliaryRuntimeConfig config,
            String text,
            Consumer<RealtimeSpeechEvent> events) {
        requireDashScope(config);
        if (text == null || text.isBlank()) throw new IllegalArgumentException("朗读内容不能为空");
        UpstreamSession session = new UpstreamSession(events, Protocol.TTS, text.strip());
        session.connect(realtimeUri(config.baseUrl(), realtimeModel(config.model(), ttsModel, "tts")), config.apiKey());
        return session;
    }

    static URI realtimeUri(String baseUrl, String model) {
        URI base = URI.create(baseUrl);
        String host = base.getHost();
        if (!"dashscope.aliyuncs.com".equals(host)) {
            throw new IllegalArgumentException("实时语音仅支持阿里云百炼地址");
        }
        return URI.create("wss://" + host + "/api-ws/v1/realtime?model=" + model);
    }

    static String realtimeModel(String configured, String fallback, String kind) {
        String model = configured == null ? "" : configured.strip();
        if (model.contains("realtime")) return model;
        if (kind.equals("asr") && model.startsWith("qwen3-asr-flash")) return "qwen3-asr-flash-realtime";
        if (kind.equals("tts") && model.startsWith("qwen3-tts-flash")) return "qwen3-tts-flash-realtime";
        return fallback;
    }

    private void requireDashScope(AuxiliaryRuntimeConfig config) {
        String provider = config.provider() == null ? "" : config.provider().strip().toUpperCase(Locale.ROOT);
        if (!(provider.contains("DASHSCOPE") || provider.contains("ALIYUN")
                || provider.contains("阿里") || config.baseUrl().contains("aliyuncs.com"))) {
            throw new IllegalStateException("实时语音需要管理员配置阿里云百炼 ASR/TTS");
        }
    }

    public interface RealtimeSession extends AutoCloseable {
        void appendPcm(String base64Pcm);
        void finish();
        void cancel();
        @Override default void close() { cancel(); }
    }

    private enum Protocol { ASR, TTS }

    private final class UpstreamSession implements RealtimeSession, WebSocket.Listener {
        private final Consumer<RealtimeSpeechEvent> events;
        private final Protocol protocol;
        private final String text;
        private final AtomicReference<WebSocket> socket = new AtomicReference<>();
        private final Queue<String> pending = new ConcurrentLinkedQueue<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean ttsInputSent = new AtomicBoolean();
        private final StringBuilder incoming = new StringBuilder();

        private UpstreamSession(Consumer<RealtimeSpeechEvent> events, Protocol protocol, String text) {
            this.events = Objects.requireNonNull(events);
            this.protocol = protocol;
            this.text = text;
        }

        private void connect(URI uri, String apiKey) {
            httpClient().newWebSocketBuilder()
                    .header("Authorization", "Bearer " + apiKey)
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(uri, this)
                    .exceptionally(error -> {
                        emitError("实时语音服务连接失败");
                        return null;
                    });
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            socket.set(webSocket);
            if (protocol == Protocol.ASR) send(Map.of(
                    "event_id", eventId(),
                    "type", "session.update",
                    "session", Map.of(
                            "input_audio_format", "pcm",
                            "sample_rate", 16_000,
                            "input_audio_transcription", Map.of("language", "zh"),
                            "turn_detection", Map.of(
                                    "type", "server_vad",
                                    "threshold", 0.0,
                                    "silence_duration_ms", vadSilenceDuration))));
            else send(Map.of(
                    "event_id", eventId(),
                    "type", "session.update",
                    "session", Map.of(
                            "voice", voice,
                            "mode", "commit",
                            "language_type", "Chinese",
                            "response_format", "pcm",
                            "sample_rate", 24_000)));
            flushPending();
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            incoming.append(data);
            if (last) {
                String payload = incoming.toString();
                incoming.setLength(0);
                handle(payload);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.set(true);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closed.set(true);
            emitError("实时语音连接中断");
        }

        @Override
        public void appendPcm(String base64Pcm) {
            if (protocol != Protocol.ASR || closed.get()) return;
            send(Map.of("event_id", eventId(), "type", "input_audio_buffer.append", "audio", base64Pcm));
        }

        @Override
        public void finish() {
            if (closed.get()) return;
            send(Map.of("event_id", eventId(), "type", "session.finish"));
        }

        @Override
        public void cancel() {
            if (!closed.compareAndSet(false, true)) return;
            WebSocket active = socket.get();
            if (active != null) active.sendClose(WebSocket.NORMAL_CLOSURE, "cancelled");
            pending.clear();
        }

        private void handle(String payload) {
            try {
                JsonNode message = objectMapper.readTree(payload);
                String type = message.path("type").asText();
                if (protocol == Protocol.ASR) handleAsr(type, message);
                else handleTts(type, message);
            } catch (Exception exception) {
                emitError("实时语音响应无法解析");
            }
        }

        private void handleAsr(String type, JsonNode message) {
            switch (type) {
                case "session.updated" -> events.accept(RealtimeSpeechEvent.signal("asr_ready"));
                case "input_audio_buffer.speech_started" -> events.accept(RealtimeSpeechEvent.signal("speech_started"));
                case "input_audio_buffer.speech_stopped" -> events.accept(RealtimeSpeechEvent.signal("speech_stopped"));
                case "conversation.item.input_audio_transcription.text" -> events.accept(
                        RealtimeSpeechEvent.text("transcript_delta",
                                message.path("text").asText() + message.path("stash").asText()));
                case "conversation.item.input_audio_transcription.completed" -> events.accept(
                        RealtimeSpeechEvent.text("transcript_final", message.path("transcript").asText()));
                case "conversation.item.input_audio_transcription.failed", "error" -> emitError(providerMessage(message));
                case "session.finished" -> cancel();
                default -> { }
            }
        }

        private void handleTts(String type, JsonNode message) {
            switch (type) {
                case "session.updated" -> sendTtsInput();
                case "response.audio.delta" -> events.accept(RealtimeSpeechEvent.audio(message.path("delta").asText()));
                case "response.audio.done" -> events.accept(RealtimeSpeechEvent.signal("tts_done"));
                case "error" -> emitError(providerMessage(message));
                case "session.finished" -> cancel();
                default -> { }
            }
        }

        private void sendTtsInput() {
            if (!ttsInputSent.compareAndSet(false, true)) return;
            events.accept(RealtimeSpeechEvent.signal("tts_started"));
            send(Map.of("event_id", eventId(), "type", "input_text_buffer.append", "text", text));
            send(Map.of("event_id", eventId(), "type", "input_text_buffer.commit"));
            send(Map.of("event_id", eventId(), "type", "session.finish"));
        }

        private String providerMessage(JsonNode message) {
            String detail = message.path("error").path("message").asText();
            return detail.isBlank() ? "实时语音服务返回错误" : detail;
        }

        private void emitError(String detail) {
            events.accept(RealtimeSpeechEvent.error(detail));
        }

        private void send(Map<String, Object> value) {
            try {
                sendText(objectMapper.writeValueAsString(value));
            } catch (Exception exception) {
                emitError("实时语音请求无法编码");
            }
        }

        private void sendText(String value) {
            WebSocket active = socket.get();
            if (active == null) {
                pending.add(value);
                return;
            }
            if (!closed.get()) active.sendText(value, true);
        }

        private void flushPending() {
            String value;
            while ((value = pending.poll()) != null) sendText(value);
        }
    }

    private HttpClient httpClient() {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(connectTimeout);
        if (!proxyHost.isBlank() && proxyPort > 0) {
            builder.proxy(new SpeechProxySelector(proxyHost, proxyPort));
        }
        return builder.build();
    }

    private static String eventId() {
        return "event_" + UUID.randomUUID();
    }

    private static final class SpeechProxySelector extends ProxySelector {
        private final Proxy proxy;

        private SpeechProxySelector(String host, int port) {
            this.proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port));
        }

        @Override
        public List<Proxy> select(URI uri) {
            return List.of(proxy);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress address, IOException exception) {
            // HttpClient reports the failure to the WebSocket completion stage.
        }
    }
}
