package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.projectardor.platform.model.llm.service.CompatibleLlmGateway;
import com.projectardor.platform.model.llm.service.LlmGateway;
import com.projectardor.platform.model.websearch.service.TavilySearchService;
import com.projectardor.platform.model.websearch.service.WebSearchResult;
import com.projectardor.platform.usage.QuotaExceededException;
import com.projectardor.platform.usage.QuotaService;
import com.projectardor.platform.usage.QuotaStore;
import com.projectardor.platform.usage.UsageFeature;

class AgentModelDescriptionServiceTests {

    private static final String FINGERPRINT = "a".repeat(64);
    private static final String CHANGED_FINGERPRINT = "b".repeat(64);

    private final UUID user = UUID.randomUUID();
    private final TavilySearchService search = mock(TavilySearchService.class);
    private final CompatibleLlmGateway llm = mock(CompatibleLlmGateway.class);
    private final QuotaService quotas = mock(QuotaService.class);
    private final InMemoryStore store = new InMemoryStore();

    @BeforeEach
    void searchIsConfigured() {
        when(search.available(any())).thenReturn(true);
    }

    @Test
    void researchesOnceAndReusesThePersistedDescription() {
        when(search.search(eq(user), anyString())).thenReturn(evidence("A fast model with published low pricing."));
        when(llm.completeJson(eq(user), anyString(), anyString()))
                .thenReturn(new LlmGateway.LlmResult("高性价比的长文本模型", "primary"));
        var service = service(Clock.systemUTC());

        assertThat(service.describe(user, "Example-Fast", "zh", FINGERPRINT)).isEqualTo("高性价比的长文本模型");
        assertThat(service.describe(user, "example-fast", "zh", FINGERPRINT)).isEqualTo("高性价比的长文本模型");

        verify(search, times(1)).search(eq(user), anyString());
        verify(llm, times(1)).completeJson(eq(user), anyString(), anyString());
        verify(quotas, times(1)).consume(user, UsageFeature.MODEL_DESCRIPTION, null);
        assertThat(store.find(user, "example-fast", "zh")).get()
                .extracting(AgentModelDescriptionStore.Entry::fingerprint)
                .isEqualTo(FINGERPRINT);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(llm).completeJson(eq(user), anyString(), prompt.capture());
        assertThat(prompt.getValue()).contains("Example-Fast", "published low pricing");
    }

    @Test
    void changedApiConfigurationInvalidatesThePersistedDescription() {
        when(search.search(eq(user), anyString())).thenReturn(evidence("Details"));
        when(llm.completeJson(eq(user), anyString(), anyString()))
                .thenReturn(new LlmGateway.LlmResult("First summary", "primary"))
                .thenReturn(new LlmGateway.LlmResult("Second summary", "primary"));
        var service = service(Clock.systemUTC());

        assertThat(service.describe(user, "example-model", "en", FINGERPRINT)).isEqualTo("First summary");
        assertThat(service.describe(user, "example-model", "en", CHANGED_FINGERPRINT)).isEqualTo("Second summary");

        verify(search, times(2)).search(eq(user), anyString());
        verify(quotas, times(2)).consume(user, UsageFeature.MODEL_DESCRIPTION, null);
        assertThat(store.find(user, "example-model", "en")).get()
                .extracting(AgentModelDescriptionStore.Entry::fingerprint)
                .isEqualTo(CHANGED_FINGERPRINT);
    }

    @Test
    void rejectsLegacySourceLabelsAndDoesNotImmediatelyRepurchaseThem() {
        when(search.search(eq(user), anyString())).thenReturn(evidence("Details"));
        when(llm.completeJson(eq(user), anyString(), anyString()))
                .thenReturn(new LlmGateway.LlmResult("中转站", "primary"));
        var service = service(Clock.systemUTC());

        assertThatThrownBy(() -> service.describe(user, "example-model", "zh", FINGERPRINT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不可用");
        assertThatThrownBy(() -> service.describe(user, "example-model", "zh", FINGERPRINT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("暂不可用");

        verify(search, times(1)).search(eq(user), anyString());
        verify(llm, times(1)).completeJson(eq(user), anyString(), anyString());
        verify(quotas, times(1)).consume(user, UsageFeature.MODEL_DESCRIPTION, null);
    }

    @Test
    void expiredFailureMayBeResearchedAgain() {
        when(search.search(eq(user), anyString())).thenReturn(evidence("Details"));
        when(llm.completeJson(eq(user), anyString(), anyString()))
                .thenReturn(new LlmGateway.LlmResult("中转站", "primary"))
                .thenReturn(new LlmGateway.LlmResult("顶级旗舰", "primary"));

        assertThatThrownBy(() -> service(Clock.systemUTC()).describe(user, "example-model", "zh", FINGERPRINT))
                .isInstanceOf(IllegalStateException.class);
        assertThat(service(Clock.offset(Clock.systemUTC(), Duration.ofHours(2)))
                .describe(user, "example-model", "zh", FINGERPRINT)).isEqualTo("顶级旗舰");
        verify(quotas, times(2)).consume(user, UsageFeature.MODEL_DESCRIPTION, null);
    }

    @Test
    void rejectsUnboundedModelNamesAndInvalidFingerprintsAtTheHttpBoundary() {
        var service = service(Clock.systemUTC());

        assertThatThrownBy(() -> service.describe(user, "x".repeat(201), "en", FINGERPRINT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.describe(user, "example-model", "en", "not-a-fingerprint"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(search, llm, quotas);
    }

    @Test
    void doesNotShareResearchedDescriptionsAcrossUsers() {
        UUID other = UUID.randomUUID();
        when(search.search(eq(user), anyString())).thenReturn(evidence("Details"));
        when(search.search(eq(other), anyString())).thenReturn(evidence("Details"));
        when(llm.completeJson(eq(user), anyString(), anyString())).thenReturn(new LlmGateway.LlmResult("First user", "primary"));
        when(llm.completeJson(eq(other), anyString(), anyString())).thenReturn(new LlmGateway.LlmResult("Second user", "primary"));
        var service = service(Clock.systemUTC());

        assertThat(service.describe(user, "example-model", "en", FINGERPRINT)).isEqualTo("First user");
        assertThat(service.describe(other, "example-model", "en", FINGERPRINT)).isEqualTo("Second user");
        assertThat(service.describe(user, "example-model", "en", FINGERPRINT)).isEqualTo("First user");

        verify(quotas).consume(user, UsageFeature.MODEL_DESCRIPTION, null);
        verify(quotas).consume(other, UsageFeature.MODEL_DESCRIPTION, null);
    }

    @Test
    void unconfiguredSearchDoesNotConsumeTheDescriptionAllowanceOrPersistFailure() {
        when(search.available(user)).thenReturn(false);
        var service = service(Clock.systemUTC());

        assertThatThrownBy(() -> service.describe(user, "example-model", "en", FINGERPRINT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("尚未配置");
        verifyNoInteractions(quotas, llm);
        assertThat(store.find(user, "example-model", "en")).isEmpty();
    }

    @Test
    void quotaDenialPreventsPaidResearchAndIsNotCachedAsAModelFailure() {
        doThrow(new QuotaExceededException(UsageFeature.MODEL_DESCRIPTION,
                QuotaStore.Decision.MONTHLY_LIMIT, Instant.parse("2026-11-01T00:00:00Z")))
                .when(quotas).consume(user, UsageFeature.MODEL_DESCRIPTION, null);
        var service = service(Clock.systemUTC());

        assertThatThrownBy(() -> service.describe(user, "example-model", "en", FINGERPRINT))
                .isInstanceOf(QuotaExceededException.class);
        verifyNoInteractions(llm);
        verify(search, times(0)).search(any(), anyString());
        assertThat(store.find(user, "example-model", "en")).isEmpty();
    }

    private AgentModelDescriptionService service(Clock clock) {
        return new AgentModelDescriptionService(search, llm, quotas, store, clock);
    }

    private static WebSearchResult evidence(String content) {
        return new WebSearchResult("model", List.of(
                new WebSearchResult.ResultItem("Official model", "https://example.com/model", content, 0.9)), 1, "0.2");
    }

    private static final class InMemoryStore extends AgentModelDescriptionStore {
        private final Map<String, Entry> entries = new HashMap<>();

        InMemoryStore() {
            super(null);
        }

        @Override
        public Optional<Entry> find(UUID userId, String modelKey, String locale) {
            return Optional.ofNullable(entries.get(key(userId, modelKey, locale)));
        }

        @Override
        public void saveSuccess(UUID userId, String modelKey, String locale, String fingerprint, String description) {
            entries.put(key(userId, modelKey, locale), new Entry(fingerprint, description, Instant.now()));
        }

        @Override
        public void saveFailure(UUID userId, String modelKey, String locale, String fingerprint) {
            entries.put(key(userId, modelKey, locale), new Entry(fingerprint, null, Instant.now()));
        }

        private static String key(UUID userId, String modelKey, String locale) {
            return userId + "|" + modelKey + "|" + locale;
        }
    }
}
