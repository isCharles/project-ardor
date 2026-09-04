package com.projectardor.speech.service;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import com.projectardor.common.security.ExternalBaseUrlPolicy;

@Service
public class SpeechService {

    private static final Set<String> ALLOWED_AUDIO_TYPES = Set.of(
            "audio/webm", "audio/ogg", "audio/mp4", "audio/mpeg", "audio/wav", "audio/x-wav");

    private final AuxiliaryApiConfigService configService;
    private final RestClient restClient;
    private final ExternalBaseUrlPolicy externalBaseUrlPolicy;
    private final String openAiVoice;
    private final String dashScopeVoice;

    public SpeechService(
            AuxiliaryApiConfigService configService,
            RestClient.Builder restClientBuilder,
            ExternalBaseUrlPolicy externalBaseUrlPolicy,
            @Value("${app.speech.openai-voice:alloy}") String openAiVoice,
            @Value("${app.speech.dashscope-voice:Cherry}") String dashScopeVoice) {
        this.configService = configService;
        this.restClient = restClientBuilder.build();
        this.externalBaseUrlPolicy = externalBaseUrlPolicy;
        this.openAiVoice = openAiVoice;
        this.dashScopeVoice = dashScopeVoice;
    }

    public String transcribe(UUID userId, MultipartFile audio) {
        validateAudio(audio);
        AuxiliaryRuntimeConfig config = requiredConfig(userId, AuxiliaryServiceType.ASR);
        byte[] bytes;
        try {
            bytes = audio.getBytes();
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("无法读取录音文件", exception);
        }
        return transcribe(config, bytes, audio.getContentType(), safeFilename(audio), false);
    }

    public void probe(AuxiliaryRuntimeConfig config, AuxiliaryServiceType type) {
        if (type == AuxiliaryServiceType.ASR) {
            transcribe(config, silentWav(), "audio/wav", "probe.wav", true);
            return;
        }
        if (type == AuxiliaryServiceType.TTS) {
            synthesize(config, "你好");
            return;
        }
        throw new IllegalArgumentException("该服务不支持语音链路测试");
    }

    private String transcribe(
            AuxiliaryRuntimeConfig config,
            byte[] bytes,
            String contentType,
            String filename,
            boolean allowEmpty) {
        if (isDashScope(config)) {
            return transcribeDashScope(config, bytes, contentType, allowEmpty);
        }
        return transcribeOpenAi(config, bytes, contentType, filename, allowEmpty);
    }

    private String transcribeOpenAi(
            AuxiliaryRuntimeConfig config,
            byte[] bytes,
            String contentType,
            String filename,
            boolean allowEmpty) {
        ByteArrayResource resource = new NamedByteArrayResource(bytes, filename);

        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(mediaType(contentType));
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
            if (!allowEmpty && (response == null || response.text() == null || response.text().isBlank())) {
                throw new SpeechCallException("ASR_EMPTY_RESPONSE", "语音识别没有返回文字，请重试", true, null);
            }
            return response == null || response.text() == null ? "" : response.text().strip();
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
        return synthesize(config, text);
    }

    private SpeechAudio synthesize(AuxiliaryRuntimeConfig config, String text) {
        if (isDashScope(config)) return synthesizeDashScope(config, text);
        try {
            byte[] audio = restClient.post()
                    .uri(endpoint(config.baseUrl(), "audio/speech"))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.valueOf("audio/mpeg"), MediaType.APPLICATION_OCTET_STREAM)
                    .body(Map.of(
                            "model", config.model(),
                            "voice", openAiVoice,
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

    private String transcribeDashScope(
            AuxiliaryRuntimeConfig config,
            byte[] bytes,
            String contentType,
            boolean allowEmpty) {
        if (bytes.length > 7_500_000) {
            throw new IllegalArgumentException("百炼单段录音编码后不能超过 10 MB，请缩短回答");
        }
        String dataUrl = "data:" + mediaType(contentType) + ";base64,"
                + Base64.getEncoder().encodeToString(bytes);
        Map<String, Object> body = Map.of(
                "model", config.model(),
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", List.of(Map.of(
                                "type", "input_audio",
                                "input_audio", Map.of("data", dataUrl))))),
                "stream", false,
                "asr_options", Map.of("enable_itn", true));
        try {
            DashScopeAsrResponse response = restClient.post()
                    .uri(endpoint(config.baseUrl(), "chat/completions"))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(DashScopeAsrResponse.class);
            String text = response == null ? "" : response.text();
            if (!allowEmpty && text.isBlank()) {
                throw new SpeechCallException("ASR_EMPTY_RESPONSE", "语音识别没有返回文字，请重试", true, null);
            }
            return text.strip();
        } catch (SpeechCallException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            throw providerFailure("ASR", exception);
        } catch (RestClientException exception) {
            throw transportFailure("ASR", exception);
        }
    }

    private SpeechAudio synthesizeDashScope(AuxiliaryRuntimeConfig config, String text) {
        Map<String, Object> body = Map.of(
                "model", config.model(),
                "input", Map.of(
                        "text", text,
                        "voice", dashScopeVoice,
                        "language_type", "Chinese"));
        try {
            DashScopeTtsResponse response = restClient.post()
                    .uri(endpoint(config.baseUrl(), "services/aigc/multimodal-generation/generation"))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(DashScopeTtsResponse.class);
            String audioUrl = response == null ? null : response.audioUrl();
            if (audioUrl == null || audioUrl.isBlank()) {
                throw new SpeechCallException("TTS_EMPTY_RESPONSE", "百炼没有返回合成音频", true, null);
            }
            String safeAudioUrl = externalBaseUrlPolicy.normalizeAndValidate(audioUrl);
            ResponseEntity<byte[]> audioResponse = restClient.get()
                    // DashScope returns a pre-signed OSS URL. Passing it as a String makes
                    // RestClient expand and encode the already-encoded signature again,
                    // which changes values such as %2B and makes OSS reject the request.
                    .uri(URI.create(safeAudioUrl))
                    .accept(MediaType.ALL)
                    .retrieve()
                    .toEntity(byte[].class);
            byte[] audio = audioResponse.getBody();
            if (audio == null || audio.length == 0) {
                throw new SpeechCallException("TTS_EMPTY_RESPONSE", "百炼音频下载失败", true, null);
            }
            MediaType mediaType = audioResponse.getHeaders().getContentType();
            return new SpeechAudio(audio, mediaType == null ? MediaType.valueOf("audio/wav") : mediaType);
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

    private boolean isDashScope(AuxiliaryRuntimeConfig config) {
        String provider = config.provider() == null ? "" : config.provider().strip().toUpperCase();
        return provider.contains("DASHSCOPE") || provider.contains("ALIYUN")
                || provider.contains("阿里") || config.baseUrl().contains("aliyuncs.com");
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

    private byte[] silentWav() {
        int sampleRate = 16_000;
        int sampleCount = sampleRate / 4;
        int dataSize = sampleCount * 2;
        ByteBuffer wav = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        wav.putInt(36 + dataSize);
        wav.put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        wav.putInt(16).putShort((short) 1).putShort((short) 1);
        wav.putInt(sampleRate).putInt(sampleRate * 2).putShort((short) 2).putShort((short) 16);
        wav.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(dataSize);
        return wav.array();
    }

    private record TranscriptionPayload(String text) {}

    private record DashScopeAsrResponse(List<DashScopeChoice> choices) {
        String text() {
            if (choices == null || choices.isEmpty() || choices.getFirst().message() == null) return "";
            String content = choices.getFirst().message().content();
            return content == null ? "" : content;
        }
    }
    private record DashScopeChoice(DashScopeMessage message) {}
    private record DashScopeMessage(String content) {}
    private record DashScopeTtsResponse(DashScopeOutput output) {
        String audioUrl() { return output == null || output.audio() == null ? null : output.audio().url(); }
    }
    private record DashScopeOutput(DashScopeAudio audio) {}
    private record DashScopeAudio(String url) {}

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
