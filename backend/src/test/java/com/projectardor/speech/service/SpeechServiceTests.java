package com.projectardor.speech.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.mock.web.MockMultipartFile;

import com.projectardor.integrations.domain.AuxiliaryServiceType;
import com.projectardor.integrations.service.AuxiliaryApiConfigService;
import com.projectardor.integrations.service.AuxiliaryApiConfigService.AuxiliaryRuntimeConfig;
import com.projectardor.common.security.ExternalBaseUrlPolicy;

@ExtendWith(MockitoExtension.class)
class SpeechServiceTests {

    @Mock private AuxiliaryApiConfigService configService;
    @Mock private ExternalBaseUrlPolicy externalBaseUrlPolicy;

    private MockRestServiceServer server;
    private SpeechService speechService;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        speechService = new SpeechService(configService, builder, externalBaseUrlPolicy, "alloy", "Cherry");
    }

    @Test
    void transcribesAudioWithSavedUserOrSystemConfig() {
        when(configService.runtimeConfig(userId, AuxiliaryServiceType.ASR)).thenReturn(Optional.of(
                new AuxiliaryRuntimeConfig("OPENAI", "https://speech.example/v1", "whisper-1", "secret")));
        server.expect(requestTo("https://speech.example/v1/audio/transcriptions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer secret"))
                .andRespond(withSuccess("{\"text\":\" 这是我的回答。 \"}", MediaType.APPLICATION_JSON));

        String text = speechService.transcribe(userId, new MockMultipartFile(
                "audio", "answer.webm", "audio/webm", "voice".getBytes(StandardCharsets.UTF_8)));

        assertThat(text).isEqualTo("这是我的回答。");
        server.verify();
    }

    @Test
    void synthesizesQuestionAsMp3() {
        when(configService.runtimeConfig(userId, AuxiliaryServiceType.TTS)).thenReturn(Optional.of(
                new AuxiliaryRuntimeConfig("OPENAI", "https://speech.example/v1", "tts-1", "secret")));
        byte[] expected = new byte[] {1, 2, 3, 4};
        server.expect(requestTo("https://speech.example/v1/audio/speech"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer secret"))
                .andRespond(withSuccess(expected, MediaType.valueOf("audio/mpeg")));

        SpeechService.SpeechAudio audio = speechService.synthesize(userId, "请介绍你的项目亮点。");

        assertThat(audio.bytes()).containsExactly(expected);
        assertThat(audio.mediaType()).isEqualTo(MediaType.valueOf("audio/mpeg"));
        server.verify();
    }

    @Test
    void addsOpenAiV1PrefixWhenBaseUrlHasNoPath() {
        assertThat(SpeechService.endpoint("https://api.openai.com", "audio/transcriptions"))
                .isEqualTo("https://api.openai.com/v1/audio/transcriptions");
        assertThat(SpeechService.endpoint("https://gateway.example/v1", "audio/speech"))
                .isEqualTo("https://gateway.example/v1/audio/speech");
    }

    @Test
    void transcribesWithDashScopeChatCompletionProtocol() {
        when(configService.runtimeConfig(userId, AuxiliaryServiceType.ASR)).thenReturn(Optional.of(
                new AuxiliaryRuntimeConfig("DASHSCOPE", "https://dashscope.aliyuncs.com/compatible-mode/v1",
                        "qwen3-asr-flash", "secret")));
        server.expect(requestTo("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer secret"))
                .andRespond(withSuccess(
                        "{\"choices\":[{\"message\":{\"content\":\"这是百炼转写。\"}}]}",
                        MediaType.APPLICATION_JSON));

        String text = speechService.transcribe(userId, new MockMultipartFile(
                "audio", "answer.webm", "audio/webm", "voice".getBytes(StandardCharsets.UTF_8)));

        assertThat(text).isEqualTo("这是百炼转写。");
        server.verify();
    }

    @Test
    void synthesizesAndDownloadsDashScopeAudio() {
        String audioUrl = "https://dashscope-result.oss-cn-beijing.aliyuncs.com/probe.wav"
                + "?Expires=1788617750&Signature=abc%2Bdef%2Fghi%3D";
        when(configService.runtimeConfig(userId, AuxiliaryServiceType.TTS)).thenReturn(Optional.of(
                new AuxiliaryRuntimeConfig("DASHSCOPE", "https://dashscope.aliyuncs.com/api/v1",
                        "qwen3-tts-flash", "secret")));
        when(externalBaseUrlPolicy.normalizeAndValidate(audioUrl)).thenReturn(audioUrl);
        server.expect(requestTo("https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer secret"))
                .andRespond(withSuccess(
                        "{\"output\":{\"audio\":{\"url\":\"" + audioUrl + "\"}}}",
                        MediaType.APPLICATION_JSON));
        byte[] expected = new byte[] {5, 6, 7};
        server.expect(requestTo(audioUrl))
                .andExpect(method(HttpMethod.GET))
                .andExpect(request -> assertThat(request.getURI().getRawQuery())
                        .isEqualTo("Expires=1788617750&Signature=abc%2Bdef%2Fghi%3D"))
                .andRespond(withSuccess(expected, MediaType.valueOf("audio/wav")));

        SpeechService.SpeechAudio audio = speechService.synthesize(userId, "你好");

        assertThat(audio.bytes()).containsExactly(expected);
        assertThat(audio.mediaType()).isEqualTo(MediaType.valueOf("audio/wav"));
        server.verify();
    }
}
