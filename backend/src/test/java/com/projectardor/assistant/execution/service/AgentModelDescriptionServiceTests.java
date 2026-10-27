package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.projectardor.platform.model.llm.service.CompatibleLlmGateway;
import com.projectardor.platform.model.llm.service.LlmGateway;
import com.projectardor.platform.model.websearch.service.TavilySearchService;
import com.projectardor.platform.model.websearch.service.WebSearchResult;

class AgentModelDescriptionServiceTests {

    private final UUID user = UUID.randomUUID();
    private final TavilySearchService search = mock(TavilySearchService.class);
    private final CompatibleLlmGateway llm = mock(CompatibleLlmGateway.class);

    @Test
    void researchesModelAndCachesTheConciseDescription() {
        when(search.search(eq(user), anyString())).thenReturn(new WebSearchResult("model", List.of(
                new WebSearchResult.ResultItem("Official model", "https://example.com/model",
                        "A fast model with published low pricing and a long context window.", 0.9)), 1, "0.2"));
        when(llm.completeJson(eq(user), anyString(), anyString()))
                .thenReturn(new LlmGateway.LlmResult("高性价比的长文本模型", "primary"));
        var service = new AgentModelDescriptionService(search, llm);

        assertThat(service.describe(user, "example-fast", "zh")).isEqualTo("高性价比的长文本模型");
        assertThat(service.describe(user, "example-fast", "zh")).isEqualTo("高性价比的长文本模型");

        verify(search, times(1)).search(eq(user), anyString());
        verify(llm, times(1)).completeJson(eq(user), anyString(), anyString());
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(llm).completeJson(eq(user), anyString(), prompt.capture());
        assertThat(prompt.getValue()).contains("example-fast", "Official model", "published low pricing");
    }

    @Test
    void rejectsLegacySourceLabelsInsteadOfPresentingThemAsModelResearch() {
        when(search.search(eq(user), anyString())).thenReturn(new WebSearchResult("model", List.of(
                new WebSearchResult.ResultItem("Official model", "https://example.com/model", "Details", 0.9)), 1, "0.2"));
        when(llm.completeJson(eq(user), anyString(), anyString()))
                .thenReturn(new LlmGateway.LlmResult("中转站", "primary"));
        var service = new AgentModelDescriptionService(search, llm);

        assertThatThrownBy(() -> service.describe(user, "example-model", "zh"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不可用");
    }

    @Test
    void rejectsUnboundedModelNamesAtTheHttpBoundary() {
        var service = new AgentModelDescriptionService(search, llm);

        assertThatThrownBy(() -> service.describe(user, "x".repeat(201), "en"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
