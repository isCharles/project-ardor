package com.projectardor.platform.model.speech.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import com.projectardor.learning.practice.mockinterview.domain.InterviewModality;
import com.projectardor.learning.practice.mockinterview.domain.InterviewSession;
import com.projectardor.learning.practice.mockinterview.service.InterviewService;
import com.projectardor.learning.practice.mockinterview.service.QuestionTextListener;
import com.projectardor.learning.practice.mockinterview.service.QuestionTextStreamScanner.Fragment;
import com.projectardor.platform.identity.auth.domain.UserRole;
import com.projectardor.platform.identity.auth.security.ArdorPrincipal;
import com.projectardor.platform.model.integrations.domain.AuxiliaryServiceType;
import com.projectardor.platform.model.integrations.service.AuxiliaryApiConfigService;
import com.projectardor.platform.model.integrations.service.AuxiliaryApiConfigService.AuxiliaryRuntimeConfig;
import com.projectardor.platform.model.speech.realtime.DashScopeRealtimeSpeechGateway.RealtimeSession;
import com.projectardor.platform.usage.QuotaService;
import com.projectardor.platform.usage.UsageFeature;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class RealtimeSpeechWebSocketHandlerTests {

    private static final String CREATION_URL = "ws://localhost/ws/interviews/voice/new";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final InterviewService interviews = mock(InterviewService.class);
    private final AuxiliaryApiConfigService configs = mock(AuxiliaryApiConfigService.class);
    private final DashScopeRealtimeSpeechGateway gateway = mock(DashScopeRealtimeSpeechGateway.class);
    private final QuotaService quotas = mock(QuotaService.class);
    private final RealtimeSpeechWebSocketHandler handler =
            new RealtimeSpeechWebSocketHandler(objectMapper, interviews, configs, gateway, quotas);
    private final UUID userId = UUID.randomUUID();

    @Test
    void createsTheInterviewOnTheSocketAndSpeaksOnlyTheFirstQuestionWhileItIsWritten() throws Exception {
        List<String> sent = new CopyOnWriteArrayList<>();
        WebSocketSession socket = socket(CREATION_URL, sent);
        handler.afterConnectionEstablished(socket);
        RealtimeSession tts = mock(RealtimeSession.class);
        List<Consumer<RealtimeSpeechEvent>> listeners = new CopyOnWriteArrayList<>();
        when(configs.runtimeConfig(userId, AuxiliaryServiceType.TTS)).thenReturn(Optional.of(ttsConfig()));
        when(gateway.openTtsStream(any(), any())).thenAnswer(invocation -> {
            listeners.add(invocation.getArgument(1));
            return tts;
        });
        InterviewSession session = InterviewSession.create(
                userId, null, InterviewModality.VOICE, "Ardor", "Java backend engineer", null);
        when(interviews.createStreaming(eq(userId), isNull(), eq(InterviewModality.VOICE), eq("Ardor"),
                eq("Java backend engineer"), eq(5), isNull(), any(QuestionTextListener.class),
                any(BooleanSupplier.class)))
                .thenAnswer(invocation -> {
                    QuestionTextListener listener = invocation.getArgument(7);
                    listener.onQuestionText(new Fragment(0, "请先做一个自我介绍。", true));
                    listener.onQuestionText(new Fragment(1, "第二个问题不应该被朗读。", true));
                    return session;
                });

        try {
            handler.handleTextMessage(socket, new TextMessage("""
                    {"type":"create_interview","targetCompany":"Ardor","targetRole":"Java backend engineer","questionCount":5}
                    """));

            List<JsonNode> events = await(sent, event -> "interview_created".equals(event.path("type").asText()));
            assertThat(events).extracting(event -> event.path("type").asText())
                    .containsSubsequence("ready", "tts_connecting", "questions_generating", "interview_created");
            JsonNode created = last(events, "interview_created");
            assertThat(created.path("text").asText()).isEqualTo(session.getId().toString());
            assertThat(created.path("turn").asText()).isNotBlank();
            verify(quotas).consume(userId, UsageFeature.INTERVIEW_CREATE, null);
            verify(quotas).consume(userId, UsageFeature.VOICE_TTS, null);
            verify(tts).appendText("请先做一个自我介绍。");
            verify(tts).commitText();
            verify(tts).finishText();
            verify(tts, never()).appendText("第二个问题不应该被朗读。");

            listeners.getFirst().accept(RealtimeSpeechEvent.signal("tts_done"));
            List<JsonNode> afterAudio = await(sent, event -> "tts_done".equals(event.path("type").asText()));
            JsonNode done = last(afterAudio, "tts_done");
            assertThat(done.path("turn").asText()).isEqualTo(created.path("turn").asText());
            assertThat(done.path("index").asInt()).isEqualTo(1);
            verify(tts).cancel();
        } finally {
            handler.afterConnectionClosed(socket, CloseStatus.NORMAL);
        }
    }

    @Test
    void refusesToCreateAWorkWhenSpeechSynthesisIsNotConfigured() throws Exception {
        List<String> sent = new CopyOnWriteArrayList<>();
        WebSocketSession socket = socket(CREATION_URL, sent);
        handler.afterConnectionEstablished(socket);
        when(configs.runtimeConfig(userId, AuxiliaryServiceType.TTS)).thenReturn(Optional.empty());

        try {
            handler.handleTextMessage(socket, new TextMessage("""
                    {"type":"create_interview","targetRole":"Java backend engineer"}
                    """));

            List<JsonNode> events = await(sent, event -> "error".equals(event.path("type").asText()));
            assertThat(last(events, "error").path("detail").asText()).contains("语音合成");
            verifyNoInteractions(gateway);
            verify(interviews, never()).createStreaming(any(), any(), any(), any(), any(), anyInt(),
                    any(), any(), any());
        } finally {
            handler.afterConnectionClosed(socket, CloseStatus.NORMAL);
        }
    }

    @Test
    void refusesCreationCommandsOnAnExistingInterviewSocket() throws Exception {
        UUID interviewId = UUID.randomUUID();
        List<String> sent = new CopyOnWriteArrayList<>();
        WebSocketSession socket = socket("ws://localhost/ws/interviews/" + interviewId + "/voice", sent);
        when(interviews.get(userId, interviewId))
                .thenReturn(InterviewSession.create(userId, null, InterviewModality.VOICE, null, "Java"));
        handler.afterConnectionEstablished(socket);

        try {
            handler.handleTextMessage(socket, new TextMessage("""
                    {"type":"create_interview","targetRole":"Java backend engineer"}
                    """));

            JsonNode error = last(await(sent, event -> "error".equals(event.path("type").asText())), "error");
            assertThat(error.path("detail").asText()).contains("不能创建面试");
            verify(interviews, never()).createStreaming(any(), any(), any(), any(), any(), anyInt(),
                    any(), any(), any());
        } finally {
            handler.afterConnectionClosed(socket, CloseStatus.NORMAL);
        }
    }

    private WebSocketSession socket(String url, List<String> sent) throws Exception {
        WebSocketSession socket = mock(WebSocketSession.class);
        ArdorPrincipal principal = new ArdorPrincipal(userId, "voice@ardor.invalid", "", true, UserRole.USER);
        when(socket.getId()).thenReturn("voice-" + UUID.randomUUID());
        when(socket.getUri()).thenReturn(URI.create(url));
        when(socket.getPrincipal()).thenReturn(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
        when(socket.isOpen()).thenReturn(true);
        doAnswer(invocation -> {
            sent.add(((TextMessage) invocation.getArgument(0)).getPayload());
            return null;
        }).when(socket).sendMessage(any());
        return socket;
    }

    private List<JsonNode> await(List<String> sent, Predicate<JsonNode> done) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            List<JsonNode> events = sent.stream().map(objectMapper::readTree).toList();
            if (events.stream().anyMatch(done)) return events;
            Thread.sleep(20);
        }
        throw new AssertionError("timed out waiting for a voice event; received " + sent);
    }

    private static JsonNode last(List<JsonNode> events, String type) {
        return events.stream().filter(event -> type.equals(event.path("type").asText()))
                .reduce((first, second) -> second)
                .orElseThrow();
    }

    private static AuxiliaryRuntimeConfig ttsConfig() {
        return new AuxiliaryRuntimeConfig("DASHSCOPE", "https://dashscope.aliyuncs.com",
                "qwen3-tts-flash", "test-key");
    }
}
