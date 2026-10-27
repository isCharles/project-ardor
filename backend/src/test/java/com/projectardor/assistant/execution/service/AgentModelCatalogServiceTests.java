package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.projectardor.platform.model.integrations.domain.AuxiliaryServiceType;
import com.projectardor.platform.model.integrations.service.AuxiliaryApiConfigService;
import com.projectardor.platform.model.llm.domain.LlmProvider;
import com.projectardor.platform.model.llm.service.LlmConfigService;
import com.projectardor.platform.model.llm.service.LlmModelDiscoveryService;

class AgentModelCatalogServiceTests {

    @Test
    void exposesPrimaryAndEveryRelayModelWithoutLeakingKeys() {
        UUID user = UUID.randomUUID();
        var llm = mock(LlmConfigService.class);
        var auxiliary = mock(AuxiliaryApiConfigService.class);
        var discovery = mock(LlmModelDiscoveryService.class);
        when(llm.getRuntimeConfig(user)).thenReturn(new LlmConfigService.LlmRuntimeConfig(
                LlmProvider.OPENAI_COMPATIBLE, "https://api.deepseek.com", "deepseek-v4-flash", "primary-secret"));
        when(auxiliary.runtimeConfig(user, AuxiliaryServiceType.FALLBACK_LLM)).thenReturn(
                Optional.of(new AuxiliaryApiConfigService.AuxiliaryRuntimeConfig(
                        "ANTHROPIC_COMPATIBLE", "https://cf.api.fan", "claude-opus-5-5", "fallback-secret")));
        when(discovery.list(org.mockito.ArgumentMatchers.eq(LlmProvider.OPENAI_COMPATIBLE),
                org.mockito.ArgumentMatchers.eq("https://cf.api.fan/v1"), anyString()))
                .thenReturn(List.of("GPT-6.1-Sol", "Seed-2.1-Turbo", "claude-opus-5-5"));

        var catalog = new AgentModelCatalogService(llm, auxiliary, discovery);
        var options = catalog.options(user);
        assertThat(catalog.options(user)).isEqualTo(options);
        verify(discovery, times(3)).list(any(), anyString(), anyString());

        assertThat(options.getFirst().id()).isEqualTo(AgentModelCatalogService.PRIMARY);
        assertThat(options).extracting(AgentModelCatalogService.Option::id)
                .contains("FALLBACK:GPT-6.1-Sol", "FALLBACK:Seed-2.1-Turbo", "FALLBACK:claude-opus-5-5");
        assertThat(options).noneMatch(option -> "fallback-secret".equals(option.label()));
        assertThat(catalog.resolve(user, "FALLBACK:GPT-6.1-Sol").config().provider())
                .isEqualTo(LlmProvider.OPENAI_COMPATIBLE);
        assertThat(catalog.resolve(user, "FALLBACK:GPT-6.1-Sol").config().baseUrl())
                .isEqualTo("https://cf.api.fan/v1");
        assertThat(catalog.resolve(user, "FALLBACK:claude-opus-5-5").config().provider())
                .isEqualTo(LlmProvider.ANTHROPIC_COMPATIBLE);
        assertThat(catalog.resolve(user, AgentModelCatalogService.FALLBACK).config().apiKey())
                .isEqualTo("fallback-secret");
    }

    @Test
    void descriptionFingerprintIdentifiesTheResolvedApiConfigurationWithoutLeakingSecrets() {
        UUID user = UUID.randomUUID();
        var llm = mock(LlmConfigService.class);
        var auxiliary = mock(AuxiliaryApiConfigService.class);
        var discovery = mock(LlmModelDiscoveryService.class);
        when(llm.getRuntimeConfig(user)).thenReturn(new LlmConfigService.LlmRuntimeConfig(
                LlmProvider.OPENAI_COMPATIBLE, "https://api.deepseek.com", "deepseek-v4-flash", "primary-secret"));
        when(auxiliary.runtimeConfig(user, AuxiliaryServiceType.FALLBACK_LLM)).thenReturn(Optional.empty());
        var catalog = new AgentModelCatalogService(llm, auxiliary, discovery);

        String fingerprint = catalog.descriptionFingerprint(user, "deepseek-v4-flash");

        assertThat(fingerprint).matches("[0-9a-f]{64}").doesNotContain("primary-secret");
        assertThat(catalog.descriptionFingerprint(user, "deepseek-v4-flash")).isEqualTo(fingerprint);
        assertThatThrownBy(() -> catalog.descriptionFingerprint(user, "missing-model"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知");
    }

    @Test
    void descriptionFingerprintRefreshesTheCatalogWhenANewModelAppears() {
        UUID user = UUID.randomUUID();
        var llm = mock(LlmConfigService.class);
        var auxiliary = mock(AuxiliaryApiConfigService.class);
        var discovery = mock(LlmModelDiscoveryService.class);
        when(llm.getRuntimeConfig(user)).thenReturn(new LlmConfigService.LlmRuntimeConfig(
                LlmProvider.OPENAI_COMPATIBLE, "https://api.deepseek.com", "deepseek-v4-flash", "primary-secret"));
        when(auxiliary.runtimeConfig(user, AuxiliaryServiceType.FALLBACK_LLM))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new AuxiliaryApiConfigService.AuxiliaryRuntimeConfig(
                        "OPENAI_COMPATIBLE", "https://relay.example", "new-relay-model", "relay-secret")));
        when(discovery.list(org.mockito.ArgumentMatchers.eq(LlmProvider.OPENAI_COMPATIBLE),
                org.mockito.ArgumentMatchers.eq("https://relay.example"), anyString()))
                .thenReturn(List.of("new-relay-model"));
        var catalog = new AgentModelCatalogService(llm, auxiliary, discovery);
        assertThat(catalog.options(user)).extracting(AgentModelCatalogService.Option::label)
                .doesNotContain("new-relay-model");

        assertThat(catalog.descriptionFingerprint(user, "new-relay-model")).matches("[0-9a-f]{64}");
    }

    @Test
    void repeatedUnknownDescriptionLabelsDoNotBypassTheCatalogCooldown() {
        UUID user = UUID.randomUUID();
        var llm = mock(LlmConfigService.class);
        var auxiliary = mock(AuxiliaryApiConfigService.class);
        var discovery = mock(LlmModelDiscoveryService.class);
        when(llm.getRuntimeConfig(user)).thenReturn(new LlmConfigService.LlmRuntimeConfig(
                LlmProvider.OPENAI_COMPATIBLE, "https://api.deepseek.com", "deepseek-v4-flash", "primary-secret"));
        when(auxiliary.runtimeConfig(user, AuxiliaryServiceType.FALLBACK_LLM)).thenReturn(
                Optional.of(new AuxiliaryApiConfigService.AuxiliaryRuntimeConfig(
                        "OPENAI_COMPATIBLE", "https://relay.example", "known-relay-model", "relay-secret")));
        when(discovery.list(org.mockito.ArgumentMatchers.eq(LlmProvider.OPENAI_COMPATIBLE),
                org.mockito.ArgumentMatchers.eq("https://relay.example"), anyString()))
                .thenReturn(List.of("known-relay-model"));
        var catalog = new AgentModelCatalogService(llm, auxiliary, discovery);
        catalog.options(user);
        verify(discovery, times(1)).list(any(), anyString(), anyString());

        assertThatThrownBy(() -> catalog.descriptionFingerprint(user, "missing-1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> catalog.descriptionFingerprint(user, "missing-2"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> catalog.descriptionFingerprint(user, "missing-3"))
                .isInstanceOf(IllegalArgumentException.class);

        verify(discovery, times(2)).list(any(), anyString(), anyString());
    }

    @Test
    void unknownOrUnconfiguredFallbackFailsClearly() {
        UUID user = UUID.randomUUID();
        var llm = mock(LlmConfigService.class);
        var auxiliary = mock(AuxiliaryApiConfigService.class);
        var discovery = mock(LlmModelDiscoveryService.class);
        when(llm.getRuntimeConfig(user)).thenReturn(new LlmConfigService.LlmRuntimeConfig(
                LlmProvider.OPENAI_COMPATIBLE, "https://api.deepseek.com", "deepseek-v4-flash", "primary-secret"));
        when(auxiliary.runtimeConfig(user, AuxiliaryServiceType.FALLBACK_LLM)).thenReturn(Optional.empty());
        var catalog = new AgentModelCatalogService(llm, auxiliary, discovery);

        assertThatThrownBy(() -> catalog.resolve(user, AgentModelCatalogService.FALLBACK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("备用模型");
        assertThatThrownBy(() -> catalog.resolve(user, "OTHER"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知");
    }
}
