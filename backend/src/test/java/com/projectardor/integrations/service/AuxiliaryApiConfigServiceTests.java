package com.projectardor.integrations.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.projectardor.integrations.domain.AuxiliaryApiConfig;
import com.projectardor.integrations.domain.AuxiliaryServiceType;
import com.projectardor.integrations.repository.AuxiliaryApiConfigRepository;
import com.projectardor.integrations.web.AuxiliaryApiConfigRequest;
import com.projectardor.llm.security.ApiKeyCipher;
import com.projectardor.admin.service.SystemApiConfigService;
import com.projectardor.admin.domain.SystemApiServiceType;
import com.projectardor.admin.web.AdminApiConfigResponse;

class AuxiliaryApiConfigServiceTests {

    private final AuxiliaryApiConfigRepository repository = mock(AuxiliaryApiConfigRepository.class);
    private final ApiKeyCipher cipher = new ApiKeyCipher("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=");
    private final SystemApiConfigService systemConfigService = mock(SystemApiConfigService.class);
    private final AuxiliaryApiConfigService service = new AuxiliaryApiConfigService(
            repository, cipher, new com.projectardor.common.security.ExternalBaseUrlPolicy(true), systemConfigService);

    @Test
    void listsEveryReservedServiceEvenBeforeConfiguration() {
        UUID userId = UUID.randomUUID();
        when(repository.findAllByUserId(userId)).thenReturn(List.of());
        java.util.Arrays.stream(AuxiliaryServiceType.values()).forEach(type ->
                when(systemConfigService.view(com.projectardor.admin.domain.SystemApiServiceType.valueOf(type.name())))
                        .thenReturn(Optional.empty()));

        var result = service.list(userId);

        assertThat(result).extracting(item -> item.serviceType())
                .containsExactly(AuxiliaryServiceType.values());
        assertThat(result).allMatch(item -> !item.configured());
    }

    @Test
    void validatesBaseUrlBeforeSavingReservedConfiguration() {
        UUID userId = UUID.randomUUID();
        when(repository.findByUserIdAndServiceType(userId, AuxiliaryServiceType.ASR))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.upsert(
                userId,
                AuxiliaryServiceType.ASR,
                new AuxiliaryApiConfigRequest("Example", "not-a-url", "speech-model", "secret")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Base URL");
    }

    @Test
    void savesEncryptedReservedConfigurationAndReturnsOnlyHint() {
        UUID userId = UUID.randomUUID();
        when(repository.findByUserIdAndServiceType(userId, AuxiliaryServiceType.EMBEDDING))
                .thenReturn(Optional.empty());
        when(repository.save(any(AuxiliaryApiConfig.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.upsert(
                userId,
                AuxiliaryServiceType.EMBEDDING,
                new AuxiliaryApiConfigRequest("OpenAI", "https://api.example.com/", "embedding-model", "secret-4321"));

        assertThat(response.configured()).isTrue();
        assertThat(response.baseUrl()).isEqualTo("https://api.example.com");
        assertThat(response.keyHint()).isEqualTo("••••4321");
    }

    @Test
    void testConnectionCanReuseEncryptedSavedKey() {
        UUID userId = UUID.randomUUID();
        AuxiliaryApiConfig config = AuxiliaryApiConfig.create(userId, AuxiliaryServiceType.TTS);
        ApiKeyCipher.EncryptedValue encrypted = cipher.encrypt(userId, "saved-secret");
        config.update("OpenAI", "https://api.example.com", "tts-model", encrypted.ciphertext(), encrypted.iv(), "••••cret");
        when(repository.findByUserIdAndServiceType(userId, AuxiliaryServiceType.TTS))
                .thenReturn(Optional.of(config));

        var runtime = service.runtimeConfigForTest(
                userId,
                AuxiliaryServiceType.TTS,
                new AuxiliaryApiConfigRequest("OpenAI", "https://api.example.com", "tts-model", ""));

        assertThat(runtime.apiKey()).isEqualTo("saved-secret");
    }

    @Test
    void inheritsAdministratorDefaultWithoutCreatingPersonalConfig() {
        UUID userId = UUID.randomUUID();
        when(repository.findAllByUserId(userId)).thenReturn(List.of());
        java.util.Arrays.stream(AuxiliaryServiceType.values()).forEach(type ->
                when(systemConfigService.view(SystemApiServiceType.valueOf(type.name())))
                        .thenReturn(Optional.empty()));
        when(systemConfigService.view(SystemApiServiceType.EMBEDDING)).thenReturn(Optional.of(
                new AdminApiConfigResponse(SystemApiServiceType.EMBEDDING, true, "OpenAI",
                        "https://api.example.com", "embed-model", "••••1234", null)));

        var embedding = service.list(userId).stream()
                .filter(item -> item.serviceType() == AuxiliaryServiceType.EMBEDDING)
                .findFirst().orElseThrow();

        assertThat(embedding.configurationSource()).isEqualTo("ADMIN");
        assertThat(embedding.personalOverride()).isFalse();
    }
}
