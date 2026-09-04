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

@ExtendWith(MockitoExtension.class)
class SpeechServiceTests {

    @Mock private AuxiliaryApiConfigService configService;

    private MockRestServiceServer server;
    private SpeechService speechService;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        speechService = new SpeechService(configService, builder, "alloy");
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
}
