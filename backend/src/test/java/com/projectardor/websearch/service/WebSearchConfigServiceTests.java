package com.projectardor.websearch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.projectardor.llm.security.ApiKeyCipher;
import com.projectardor.admin.service.SystemApiConfigService;
import com.projectardor.websearch.domain.WebSearchConfig;
import com.projectardor.websearch.repository.WebSearchConfigRepository;
import com.projectardor.websearch.web.WebSearchConfigRequest;

class WebSearchConfigServiceTests {

    private final WebSearchConfigRepository repository = mock(WebSearchConfigRepository.class);
    private final ApiKeyCipher cipher = new ApiKeyCipher("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=");
    private final SystemApiConfigService systemConfigService = mock(SystemApiConfigService.class);
    private final WebSearchConfigService service = new WebSearchConfigService(repository, cipher, systemConfigService);

    @Test
    void encryptsNewTavilyKeyAndOnlyReturnsHint() {
        UUID userId = UUID.randomUUID();
        when(repository.findByUserId(userId)).thenReturn(Optional.empty());
        when(repository.save(any(WebSearchConfig.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.upsert(userId, new WebSearchConfigRequest("tvly-secret-9876"));

        assertThat(response.configured()).isTrue();
        assertThat(response.keyHint()).isEqualTo("••••9876");
        verify(repository).save(any(WebSearchConfig.class));
    }

    @Test
    void requiresKeyForFirstConfiguration() {
        UUID userId = UUID.randomUUID();
        when(repository.findByUserId(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.upsert(userId, new WebSearchConfigRequest(" ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("首次配置");
    }

    @Test
    void inheritsAdministratorTavilyConfigWhenPersonalConfigIsAbsent() {
        UUID userId = UUID.randomUUID();
        when(repository.findByUserId(userId)).thenReturn(Optional.empty());
        when(systemConfigService.view(com.projectardor.admin.domain.SystemApiServiceType.WEB_SEARCH))
                .thenReturn(Optional.of(new com.projectardor.admin.web.AdminApiConfigResponse(
                        com.projectardor.admin.domain.SystemApiServiceType.WEB_SEARCH, true, "Tavily",
                        "https://api.tavily.com", null, "••••1234", null)));

        var response = service.get(userId);

        assertThat(response.configurationSource()).isEqualTo("ADMIN");
        assertThat(response.personalOverride()).isFalse();
    }
}
