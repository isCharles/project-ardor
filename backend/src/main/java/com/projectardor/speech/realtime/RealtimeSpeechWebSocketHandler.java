package com.projectardor.speech.realtime;

import java.io.IOException;
import java.net.URI;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.projectardor.auth.security.ArdorPrincipal;
import com.projectardor.integrations.domain.AuxiliaryServiceType;
import com.projectardor.integrations.service.AuxiliaryApiConfigService;
import com.projectardor.interview.domain.InterviewModality;
import com.projectardor.interview.service.InterviewService;
import com.projectardor.speech.realtime.DashScopeRealtimeSpeechGateway.RealtimeSession;
import com.projectardor.usage.QuotaExceededException;
import com.projectardor.usage.QuotaService;
import com.projectardor.usage.UsageFeature;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class RealtimeSpeechWebSocketHandler extends TextWebSocketHandler {

    private static final Pattern PATH = Pattern.compile("/ws/interviews/([0-9a-fA-F-]{36})/voice/?");
    private static final int MAX_AUDIO_MESSAGE = 96_000;
    private static final long MAX_AUDIO_SESSION_CHARACTERS = 13_000_000;

    private final ObjectMapper objectMapper;
    private final InterviewService interviewService;
    private final AuxiliaryApiConfigService configService;
    private final DashScopeRealtimeSpeechGateway gateway;
    private final QuotaService quotas;
    private final Map<String, LiveSession> liveSessions = new ConcurrentHashMap<>();

    public RealtimeSpeechWebSocketHandler(
            ObjectMapper objectMapper,
            InterviewService interviewService,
            AuxiliaryApiConfigService configService,
            DashScopeRealtimeSpeechGateway gateway,
            QuotaService quotas) {
        this.objectMapper = objectMapper;
        this.interviewService = interviewService;
        this.configService = configService;
        this.gateway = gateway;
        this.quotas = quotas;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        ArdorPrincipal principal = principal(session.getPrincipal());
        UUID interviewId = interviewId(session.getUri());
        if (principal == null || interviewId == null) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("请先登录"));
            return;
        }
        var interview = interviewService.get(principal.userId(), interviewId);
        if (interview.getModality() != InterviewModality.VOICE) {
            session.close(CloseStatus.NOT_ACCEPTABLE.withReason("当前面试不是语音面试"));
            return;
        }
        LiveSession live = new LiveSession(
                new ConcurrentWebSocketSessionDecorator(session, 10_000, 2 * 1024 * 1024),
                principal.userId(), interviewId);
        liveSessions.put(session.getId(), live);
        live.send(RealtimeSpeechEvent.signal("ready"));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        LiveSession live = liveSessions.get(session.getId());
        if (live == null) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        try {
            JsonNode payload = objectMapper.readTree(message.getPayload());
            switch (payload.path("type").asText()) {
                case "start_asr" -> live.startAsr();
                case "audio" -> live.appendAudio(payload.path("audio").asText());
                case "finish_asr" -> live.finishAsr();
                case "speak_question" -> live.speakQuestion(requiredUuid(payload, "questionId"));
                case "interrupt_tts" -> live.interruptTts();
                default -> live.send(RealtimeSpeechEvent.error("不支持的实时语音指令"));
            }
        } catch (IllegalArgumentException | IllegalStateException | QuotaExceededException exception) {
            live.send(RealtimeSpeechEvent.error(exception.getMessage()));
        } catch (Exception exception) {
            live.send(RealtimeSpeechEvent.error("实时语音指令无法处理"));
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        remove(session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        remove(session.getId());
    }

    private void remove(String sessionId) {
        LiveSession live = liveSessions.remove(sessionId);
        if (live != null) live.close();
    }

    private UUID requiredUuid(JsonNode payload, String field) {
        try {
            return UUID.fromString(payload.path(field).asText());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(field + " 无效");
        }
    }

    private ArdorPrincipal principal(Principal principal) {
        if (principal instanceof Authentication authentication
                && authentication.getPrincipal() instanceof ArdorPrincipal ardorPrincipal) {
            return ardorPrincipal;
        }
        return null;
    }

    private UUID interviewId(URI uri) {
        if (uri == null) return null;
        Matcher matcher = PATH.matcher(uri.getPath());
        if (!matcher.matches()) return null;
        try {
            return UUID.fromString(matcher.group(1));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private final class LiveSession implements AutoCloseable {
        private final WebSocketSession browser;
        private final UUID userId;
        private final UUID interviewId;
        private final AtomicReference<RealtimeSession> asr = new AtomicReference<>();
        private final AtomicReference<RealtimeSession> tts = new AtomicReference<>();
        private long audioCharacters;

        private LiveSession(WebSocketSession browser, UUID userId, UUID interviewId) {
            this.browser = browser;
            this.userId = userId;
            this.interviewId = interviewId;
        }

        private void startAsr() {
            var config = configService.runtimeConfig(userId, AuxiliaryServiceType.ASR)
                    .orElseThrow(() -> new IllegalStateException("管理员尚未配置语音识别服务"));
            quotas.consume(userId, UsageFeature.VOICE_ASR, null);
            close(asr.getAndSet(null));
            audioCharacters = 0;
            asr.set(gateway.openAsr(config, this::onAsrEvent));
            send(RealtimeSpeechEvent.signal("asr_connecting"));
        }

        private void appendAudio(String audio) {
            if (audio == null || audio.isBlank() || audio.length() > MAX_AUDIO_MESSAGE) {
                send(RealtimeSpeechEvent.error("音频帧无效"));
                return;
            }
            audioCharacters += audio.length();
            if (audioCharacters > MAX_AUDIO_SESSION_CHARACTERS) {
                close(asr.getAndSet(null));
                send(RealtimeSpeechEvent.error("单次回答最长 5 分钟，请精简后重试"));
                return;
            }
            RealtimeSession active = asr.get();
            if (active != null) active.appendPcm(audio);
        }

        private void finishAsr() {
            RealtimeSession active = asr.get();
            if (active != null) active.finish();
        }

        private void speakQuestion(UUID questionId) {
            String question = interviewService.getQuestion(userId, interviewId, questionId).getQuestionText();
            var config = configService.runtimeConfig(userId, AuxiliaryServiceType.TTS)
                    .orElseThrow(() -> new IllegalStateException("管理员尚未配置语音合成服务"));
            quotas.consume(userId, UsageFeature.VOICE_TTS, null);
            close(tts.getAndSet(null));
            tts.set(gateway.openTts(config, question, this::onTtsEvent));
            send(RealtimeSpeechEvent.signal("tts_connecting"));
        }

        private void interruptTts() {
            RealtimeSession active = tts.getAndSet(null);
            if (active != null) {
                active.cancel();
                send(RealtimeSpeechEvent.signal("tts_interrupted"));
            }
        }

        private void onAsrEvent(RealtimeSpeechEvent event) {
            if (event.type().equals("speech_started")) interruptTts();
            if (event.type().equals("transcript_final") || event.type().equals("error")) {
                close(asr.getAndSet(null));
            }
            send(event);
        }

        private void onTtsEvent(RealtimeSpeechEvent event) {
            if (event.type().equals("tts_done") || event.type().equals("error")) {
                close(tts.getAndSet(null));
            }
            send(event);
        }

        private void send(RealtimeSpeechEvent event) {
            if (!browser.isOpen()) return;
            try {
                browser.sendMessage(new TextMessage(objectMapper.writeValueAsString(event)));
            } catch (IOException exception) {
                close();
            }
        }

        @Override
        public void close() {
            close(asr.getAndSet(null));
            close(tts.getAndSet(null));
        }

        private void close(RealtimeSession session) {
            if (session != null) session.cancel();
        }
    }
}
