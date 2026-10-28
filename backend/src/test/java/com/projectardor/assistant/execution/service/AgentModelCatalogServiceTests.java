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
import java.util.Map;
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

    @Test
    void mergesOwnerSettingsIntoTheCatalogAndScopesTheLookupToThatOwner() {
        UUID user = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        var llm = mock(LlmConfigService.class);
        var auxiliary = mock(AuxiliaryApiConfigService.class);
        var discovery = mock(LlmModelDiscoveryService.class);
        var settings = mock(UserModelSettingsStore.class);
        when(llm.getRuntimeConfig(user)).thenReturn(new LlmConfigService.LlmRuntimeConfig(
                LlmProvider.OPENAI_COMPATIBLE, "https://api.deepseek.com", "deepseek-v4-flash", "primary-secret"));
        when(auxiliary.runtimeConfig(user, AuxiliaryServiceType.FALLBACK_LLM)).thenReturn(
                Optional.of(new AuxiliaryApiConfigService.AuxiliaryRuntimeConfig(
                        "ANTHROPIC_COMPATIBLE", "https://cf.api.fan", "claude-opus-5-5", "fallback-secret")));
        when(discovery.list(org.mockito.ArgumentMatchers.eq(LlmProvider.OPENAI_COMPATIBLE),
                org.mockito.ArgumentMatchers.eq("https://cf.api.fan/v1"), anyString()))
                .thenReturn(List.of("GPT-6.1-Sol"));
        var relayConfig = new LlmConfigService.LlmRuntimeConfig(
                LlmProvider.OPENAI_COMPATIBLE, "https://cf.api.fan/v1", "GPT-6.1-Sol", "fallback-secret");
        String fingerprint = ModelConfigFingerprint.of(relayConfig);
        when(settings.findAll(user)).thenReturn(Map.of(fingerprint,
                new UserModelSettingsStore.Setting(fingerprint, false, "DEEP", 400_000L)));

        var options = new AgentModelCatalogService(llm, auxiliary, discovery, settings).options(user);

        var relay = options.stream().filter(option -> option.id().equals("FALLBACK:GPT-6.1-Sol"))
                .findFirst().orElseThrow();
        assertThat(relay.visible()).isFalse();
        assertThat(relay.thinkingEffort()).isEqualTo("DEEP");
        assertThat(relay.contextWindowTokens()).isEqualTo(400_000L);
        var primary = options.stream().filter(option -> option.id().equals(AgentModelCatalogService.PRIMARY))
                .findFirst().orElseThrow();
        assertThat(primary.visible()).isTrue();
        assertThat(primary.thinkingEffort()).isEqualTo("AUTO");
        assertThat(primary.contextWindowTokens()).isNull();
        verify(settings).findAll(user);
        verify(settings, org.mockito.Mockito.never()).findAll(other);
    }

    @Test
    void updateSettingsValidatesThenPersistsTheResolvedConfigurationAndReloadsTheCatalog() {
        UUID user = UUID.randomUUID();
        var llm = mock(LlmConfigService.class);
        var auxiliary = mock(AuxiliaryApiConfigService.class);
        var discovery = mock(LlmModelDiscoveryService.class);
        var settings = mock(UserModelSettingsStore.class);
        when(llm.getRuntimeConfig(user)).thenReturn(new LlmConfigService.LlmRuntimeConfig(
                LlmProvider.OPENAI_COMPATIBLE, "https://api.deepseek.com", "deepseek-v4-flash", "primary-secret"));
        when(auxiliary.runtimeConfig(user, AuxiliaryServiceType.FALLBACK_LLM)).thenReturn(
                Optional.of(new AuxiliaryApiConfigService.AuxiliaryRuntimeConfig(
                        "ANTHROPIC_COMPATIBLE", "https://cf.api.fan", "claude-opus-5-5", "fallback-secret")));
        when(discovery.list(org.mockito.ArgumentMatchers.eq(LlmProvider.OPENAI_COMPATIBLE),
                org.mockito.ArgumentMatchers.eq("https://cf.api.fan/v1"), anyString()))
                .thenReturn(List.of("GPT-6.1-Sol"));
        when(settings.findAll(user)).thenReturn(Map.of());
        var catalog = new AgentModelCatalogService(llm, auxiliary, discovery, settings);

        assertThatThrownBy(() -> catalog.updateSettings(user, List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("没有需要保存");
        assertThatThrownBy(() -> catalog.updateSettings(user, List.of(
                new AgentModelCatalogService.SettingsUpdate("FALLBACK:GPT-6.1-Sol", true, "EXTREME", null))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("思考力度");
        assertThatThrownBy(() -> catalog.updateSettings(user, List.of(
                new AgentModelCatalogService.SettingsUpdate("FALLBACK:GPT-6.1-Sol", true, "AUTO", 1024L))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("上下文窗口");
        verify(settings, org.mockito.Mockito.never())
                .save(any(), anyString(), anyString(), anyString(), anyString(),
                        org.mockito.ArgumentMatchers.anyBoolean(), anyString(), any());

        var relayConfig = new LlmConfigService.LlmRuntimeConfig(
                LlmProvider.OPENAI_COMPATIBLE, "https://cf.api.fan/v1", "GPT-6.1-Sol", "fallback-secret");
        String fingerprint = ModelConfigFingerprint.of(relayConfig);
        when(settings.findAll(user)).thenReturn(Map.of(fingerprint,
                new UserModelSettingsStore.Setting(fingerprint, false, "QUICK", 200_000L)));
        var options = catalog.updateSettings(user, List.of(
                new AgentModelCatalogService.SettingsUpdate("FALLBACK:GPT-6.1-Sol", false, "quick", 200_000L)));

        verify(settings).save(user, fingerprint, "OPENAI_COMPATIBLE", "https://cf.api.fan/v1",
                "GPT-6.1-Sol", false, "QUICK", 200_000L);
        var relay = options.stream().filter(option -> option.id().equals("FALLBACK:GPT-6.1-Sol"))
                .findFirst().orElseThrow();
        assertThat(relay.visible()).isFalse();
        assertThat(relay.thinkingEffort()).isEqualTo("QUICK");
        assertThat(relay.contextWindowTokens()).isEqualTo(200_000L);
    }

    @Test
    void settingsStoreFailureFallsBackToDefaultsAndDirectConstructionCannotWrite() {
        UUID user = UUID.randomUUID();
        var llm = mock(LlmConfigService.class);
        var auxiliary = mock(AuxiliaryApiConfigService.class);
        var discovery = mock(LlmModelDiscoveryService.class);
        var settings = mock(UserModelSettingsStore.class);
        when(llm.getRuntimeConfig(user)).thenReturn(new LlmConfigService.LlmRuntimeConfig(
                LlmProvider.OPENAI_COMPATIBLE, "https://api.deepseek.com", "deepseek-v4-flash", "primary-secret"));
        when(auxiliary.runtimeConfig(user, AuxiliaryServiceType.FALLBACK_LLM)).thenReturn(Optional.empty());
        when(settings.findAll(user)).thenThrow(new IllegalStateException("synthetic database outage"));

        var options = new AgentModelCatalogService(llm, auxiliary, discovery, settings).options(user);

        assertThat(options).allMatch(AgentModelCatalogService.Option::visible);
        assertThat(options).allMatch(option -> "AUTO".equals(option.thinkingEffort()));
        assertThatThrownBy(() -> new AgentModelCatalogService(llm, auxiliary, discovery)
                .updateSettings(user, List.of(new AgentModelCatalogService.SettingsUpdate(
                        AgentModelCatalogService.PRIMARY, true, "AUTO", null))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("暂不可用");
    }
}
