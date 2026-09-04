package com.projectardor.llm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.projectardor.admin.domain.SystemApiServiceType;
import com.projectardor.admin.service.SystemApiConfigService;
import com.projectardor.admin.web.AdminApiConfigResponse;
import com.projectardor.common.security.ExternalBaseUrlPolicy;
import com.projectardor.llm.domain.LlmProvider;
import com.projectardor.llm.domain.LlmProviderConfig;
import com.projectardor.llm.repository.LlmProviderConfigRepository;
import com.projectardor.llm.security.ApiKeyCipher;

class LlmConfigServiceTests {
    private final LlmProviderConfigRepository repository = mock(LlmProviderConfigRepository.class);
    private final SystemApiConfigService systemConfigService = mock(SystemApiConfigService.class);
    private final ApiKeyCipher cipher = new ApiKeyCipher("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=");
    private final LlmConfigService service = new LlmConfigService(repository, cipher,
            new ExternalBaseUrlPolicy(true), systemConfigService);

    @Test
    void inheritsAdministratorDefaultWhenUserHasNoPersonalConfig() {
        UUID userId = UUID.randomUUID();
        when(repository.findByUserId(userId)).thenReturn(Optional.empty());
        when(systemConfigService.view(SystemApiServiceType.PRIMARY_LLM)).thenReturn(Optional.of(
                new AdminApiConfigResponse(SystemApiServiceType.PRIMARY_LLM, true,
                        "OPENAI_COMPATIBLE", "https://api.example.com", "shared-model", "••••1234", null)));

        var response = service.get(userId);

        assertThat(response.configurationSource()).isEqualTo("ADMIN");
        assertThat(response.personalOverride()).isFalse();
        assertThat(response.model()).isEqualTo("shared-model");
    }

    @Test
    void personalConfigurationTakesPrecedenceOverAdministratorDefault() {
        UUID userId = UUID.randomUUID();
        LlmProviderConfig personal = LlmProviderConfig.create(userId);
        ApiKeyCipher.EncryptedValue encrypted = cipher.encrypt(userId, "personal-secret");
        personal.update(LlmProvider.ANTHROPIC_COMPATIBLE, "https://api.personal.example", "personal-model",
                encrypted.ciphertext(), encrypted.iv(), "••••cret");
        when(repository.findByUserId(userId)).thenReturn(Optional.of(personal));

        var response = service.get(userId);

        assertThat(response.configurationSource()).isEqualTo("PERSONAL");
        assertThat(response.personalOverride()).isTrue();
        assertThat(response.model()).isEqualTo("personal-model");
    }
}
