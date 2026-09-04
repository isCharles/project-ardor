package com.projectardor.speech.service;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.multipart.MultipartFile;

import com.projectardor.integrations.domain.AuxiliaryServiceType;
import com.projectardor.integrations.service.AuxiliaryApiConfigService;
import com.projectardor.integrations.service.AuxiliaryApiConfigService.AuxiliaryRuntimeConfig;

@Service
public class SpeechService {

    private static final Set<String> ALLOWED_AUDIO_TYPES = Set.of(
            "audio/webm", "audio/ogg", "audio/mp4", "audio/mpeg", "audio/wav", "audio/x-wav");

    private final AuxiliaryApiConfigService configService;
    private final RestClient restClient;
    private final String defaultVoice;

    public SpeechService(
            AuxiliaryApiConfigService configService,
            RestClient.Builder restClientBuilder,
            @Value("${app.speech.default-voice:alloy}") String defaultVoice) {
        this.configService = configService;
        this.restClient = restClientBuilder.build();
        this.defaultVoice = defaultVoice;
    }

    public String transcribe(UUID userId, MultipartFile audio) {
        validateAudio(audio);
        AuxiliaryRuntimeConfig config = requiredConfig(userId, AuxiliaryServiceType.ASR);
        ByteArrayResource resource;
        try {
            resource = new NamedByteArrayResource(audio.getBytes(), safeFilename(audio));
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("无法读取录音文件", exception);
        }

        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(mediaType(audio.getContentType()));
        parts.add("file", new HttpEntity<>(resource, fileHeaders));
        parts.add("model", config.model());

        try {
            TranscriptionPayload response = restClient.post()
                    .uri(endpoint(config.baseUrl(), "audio/transcriptions"))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.apiKey())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(parts)
                    .retrieve()
                    .body(TranscriptionPayload.class);
            if (response == null || response.text() == null || response.text().isBlank()) {
                throw new SpeechCallException("ASR_EMPTY_RESPONSE", "语音识别没有返回文字，请重试", true, null);
            }
            return response.text().strip();
        } catch (SpeechCallException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            throw providerFailure("ASR", exception);
        } catch (RestClientException exception) {
            throw transportFailure("ASR", exception);
        }
    }

    public SpeechAudio synthesize(UUID userId, String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("朗读内容不能为空");
        if (text.length() > 4_000) throw new IllegalArgumentException("单次朗读内容不能超过 4000 个字符");
        AuxiliaryRuntimeConfig config = requiredConfig(userId, AuxiliaryServiceType.TTS);
        try {
            byte[] audio = restClient.post()
                    .uri(endpoint(config.baseUrl(), "audio/speech"))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.valueOf("audio/mpeg"), MediaType.APPLICATION_OCTET_STREAM)
                    .body(Map.of(
                            "model", config.model(),
                            "voice", defaultVoice,
                            "input", text,
                            "response_format", "mp3"))
                    .retrieve()
                    .body(byte[].class);
            if (audio == null || audio.length == 0) {
                throw new SpeechCallException("TTS_EMPTY_RESPONSE", "语音合成没有返回音频，请重试", true, null);
            }
            return new SpeechAudio(audio, MediaType.valueOf("audio/mpeg"));
        } catch (SpeechCallException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            throw providerFailure("TTS", exception);
        } catch (RestClientException exception) {
            throw transportFailure("TTS", exception);
        }
    }

    static String endpoint(String baseUrl, String path) {
        URI uri = URI.create(baseUrl);
        String basePath = uri.getPath();
        String normalizedBase = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
        if (basePath == null || basePath.isBlank() || "/".equals(basePath)) {
            return normalizedBase + "/v1/" + path;
        }
        return normalizedBase + "/" + path;
    }

    private AuxiliaryRuntimeConfig requiredConfig(UUID userId, AuxiliaryServiceType type) {
        return configService.runtimeConfig(userId, type)
                .orElseThrow(() -> new IllegalStateException(
                        type == AuxiliaryServiceType.ASR
                                ? "管理员尚未配置语音识别服务，请先使用文字作答"
                                : "管理员尚未配置语音合成服务，请先阅读题目文字"));
    }

    private void validateAudio(MultipartFile audio) {
        if (audio == null || audio.isEmpty()) throw new IllegalArgumentException("请先完成一段录音");
        if (audio.getSize() > 10L * 1024 * 1024) throw new IllegalArgumentException("单段录音不能超过 10 MB");
        String contentType = audio.getContentType();
        if (contentType == null || ALLOWED_AUDIO_TYPES.stream().noneMatch(contentType::startsWith)) {
            throw new IllegalArgumentException("暂不支持这种录音格式");
        }
    }

    private String safeFilename(MultipartFile audio) {
        String original = audio.getOriginalFilename();
        if (original == null || original.isBlank()) return "answer.webm";
        String leaf = java.nio.file.Path.of(original).getFileName().toString();
        return leaf.length() > 120 ? leaf.substring(leaf.length() - 120) : leaf;
    }

    private MediaType mediaType(String contentType) {
        try { return MediaType.parseMediaType(contentType); }
        catch (RuntimeException exception) { return MediaType.APPLICATION_OCTET_STREAM; }
    }

    private SpeechCallException providerFailure(String service, RestClientResponseException exception) {
        boolean retryable = exception.getStatusCode().is5xxServerError()
                || exception.getStatusCode().value() == 408
                || exception.getStatusCode().value() == 429;
        return new SpeechCallException(
                service + "_PROVIDER_ERROR",
                service + " 服务暂时不可用（HTTP " + exception.getStatusCode().value() + "）",
                retryable,
                exception);
    }

    private SpeechCallException transportFailure(String service, RestClientException exception) {
        return new SpeechCallException(
                service + "_CONNECTION_ERROR",
                "无法连接 " + service + " 服务，请检查网络后重试",
                true,
                exception);
    }

    private record TranscriptionPayload(String text) {}

    private static final class NamedByteArrayResource extends ByteArrayResource {
        private final String filename;

        private NamedByteArrayResource(byte[] bytes, String filename) {
            super(bytes);
            this.filename = filename;
        }

        @Override
        public String getFilename() { return filename; }
    }

    public record SpeechAudio(byte[] bytes, MediaType mediaType) {}
}
