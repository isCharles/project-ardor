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
import com.projectardor.websearch.domain.WebSearchConfig;
import com.projectardor.websearch.repository.WebSearchConfigRepository;
import com.projectardor.websearch.web.WebSearchConfigRequest;

class WebSearchConfigServiceTests {

    private final WebSearchConfigRepository repository = mock(WebSearchConfigRepository.class);
    private final ApiKeyCipher cipher = new ApiKeyCipher("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=");
    private final WebSearchConfigService service = new WebSearchConfigService(repository, cipher);

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
}
