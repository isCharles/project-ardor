package com.projectardor.assistant.memory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.projectardor.assistant.execution.service.AgentRunTracer;
import com.projectardor.assistant.memory.domain.AgentMemoryItem;
import com.projectardor.assistant.memory.repository.MemoryVectorStore;
import com.projectardor.assistant.memory.service.MemoryEmbeddingService.Context;
import com.projectardor.assistant.memory.service.MemoryEmbeddingService.Search;
import com.projectardor.materials.knowledge.service.KnowledgeEmbeddingService;
import com.projectardor.materials.knowledge.service.KnowledgeEmbeddingService.EmbeddingVector;

class MemoryEmbeddingServiceTests {

    private final AgentMemoryService memories = mock(AgentMemoryService.class);
    private final MemoryVectorStore vectors = mock(MemoryVectorStore.class);
    private final KnowledgeEmbeddingService embeddings = mock(KnowledgeEmbeddingService.class);
    private final MemoryEmbeddingService service = new MemoryEmbeddingService(memories, vectors, embeddings);
    private final UUID owner = UUID.randomUUID();

    private static AgentMemoryItem item(UUID owner, String content) {
        return AgentMemoryItem.create(owner, content);
    }

    private static AgentMemoryItem pinned(UUID owner, String content) {
        AgentMemoryItem item = item(owner, content);
        item.pin(true);
        return item;
    }

    @Test
    void aPlainTurnNeverAsksTheEmbeddingProviderEvenWhenMemoriesExist() {
        when(memories.validItems(owner)).thenReturn(List.of(
                pinned(owner, "目标岗位：后端开发"), item(owner, "偏好结构化面试题")));

        Context context = service.context(owner, null);

        assertThat(context.promptSection()).contains("目标岗位：后端开发").contains("偏好结构化面试题");
        assertThat(context.embeddingCalls()).isZero();
        assertThat(context.outcome()).isEqualTo("resident");
        verify(embeddings, never()).embedQuery(any(), anyString());
        verify(embeddings, never()).isConfigured(any());
    }

    @Test
    void coreMemoriesStayFirstWhenTheResidentBudgetIsTooSmall() {
        var core = pinned(owner, "核心：" + "c".repeat(400));
        var filler = new java.util.ArrayList<AgentMemoryItem>();
        for (int index = 0; index < 12; index++) {
            filler.add(item(owner, "历史记忆 " + index + "：" + "x".repeat(400)));
        }
        filler.add(0, core);
        when(memories.validItems(owner)).thenReturn(filler);

        Context context = service.context(owner, null);

        assertThat(context.promptSection()).startsWith("- 核心：");
        assertThat(context.coreAdopted()).isEqualTo(1);
        assertThat(context.outcome()).isEqualTo("budget_truncated");
        assertThat(context.omitted()).isGreaterThan(0);
        // Nothing is dropped silently: the block itself says how many memories are still searchable.
        assertThat(context.promptSection()).contains("条长期记忆未展开").contains("search_memories");
        assertThat(context.adopted() + context.omitted()).isEqualTo(context.candidates());
        assertThat(context.contextChars()).isLessThanOrEqualTo(context.characterBudget() + 200);
    }

    @Test
    void anOversizedCoreBudgetIsReportedInsteadOfVanishing() {
        var core = pinned(owner, "核心：" + "c".repeat(1300));
        when(memories.validItems(owner)).thenReturn(List.of(core));

        Context context = service.context(owner, null);

        assertThat(context.coreOmitted()).isEqualTo(1);
        assertThat(context.coreAdopted()).isZero();
        assertThat(context.promptSection()).contains("核心记忆超出常驻预算");
    }

    @Test
    void noMemoriesIsItsOwnOutcome() {
        when(memories.validItems(owner)).thenReturn(List.of());
        assertThat(service.context(owner, null).outcome()).isEqualTo("no_memories");
        assertThat(service.context(owner, null).promptSection()).isEmpty();
    }

    @Test
    void searchUsesVectorMatchesWhenAnEmbeddingModelIsConfigured() {
        var hit = item(owner, "Redis 限流用令牌桶");
        var miss = item(owner, "喜欢周日晚面试");
        when(memories.validItems(owner)).thenReturn(List.of(hit, miss));
        when(embeddings.isConfigured(owner)).thenReturn(true);
        when(embeddings.modelName(owner)).thenReturn("fixture");
        when(embeddings.embedQuery(owner, "限流")).thenReturn(new EmbeddingVector(new float[]{1f, 0f}, "fixture"));
        when(vectors.similar(eq(owner), anyString(), eq(2), eq("fixture"), anyInt()))
                .thenReturn(List.of(new MemoryVectorStore.Match(hit.getId(), 0.91)));

        Search result = service.search(owner, "限流", null, null);

        assertThat(result.retrieval()).isEqualTo("semantic");
        assertThat(result.text()).contains("Redis 限流用令牌桶").contains("相关度 0.91")
                .contains("记忆 id=" + hit.getId());
        assertThat(result.text()).doesNotContain("喜欢周日晚面试");
        assertThat(result.adopted()).isEqualTo(1);
        assertThat(result.errorCode()).isNull();
    }

    @Test
    void aFailedEmbeddingCallFallsBackToLexicalAndStaysVisible() {
        var hit = item(owner, "MySQL 事务隔离级别");
        when(memories.validItems(owner)).thenReturn(List.of(hit));
        when(embeddings.isConfigured(owner)).thenReturn(true);
        when(embeddings.modelName(owner)).thenReturn("fixture");
        when(embeddings.embedQuery(owner, "事务")).thenThrow(new IllegalStateException("relay down"));
        var tracer = mock(AgentRunTracer.class);
        UUID embeddingSpan = UUID.randomUUID();
        when(tracer.startChild(eq("CONTEXT"), eq("memory.query_embedding"), any())).thenReturn(embeddingSpan);

        Search result = service.search(owner, "事务", null, tracer);

        assertThat(result.retrieval()).isEqualTo("lexical_fallback");
        assertThat(result.errorCode()).isEqualTo("IllegalStateException");
        assertThat(result.text()).contains("MySQL 事务隔离级别");
        // The failed call must not hide behind the enclosing successful step.
        verify(tracer).finishChild(eq(embeddingSpan), eq("FAILED"), eq("IllegalStateException"),
                any(), any(), any(), any());
    }

    @Test
    void aTurnWithoutAnEmbeddingModelReportsLexicalRetrievalWithoutCallingOne() {
        var hit = item(owner, "面试复盘偏好：逐题点评");
        when(memories.validItems(owner)).thenReturn(List.of(hit));
        when(embeddings.isConfigured(owner)).thenReturn(false);

        Search result = service.search(owner, "逐题点评", null, null);

        assertThat(result.retrieval()).isEqualTo("lexical_not_configured");
        assertThat(result.text()).contains("面试复盘偏好：逐题点评");
        verify(embeddings, never()).embedQuery(any(), anyString());
    }

    @Test
    void noVectorMatchIsReportedAsNoResultsInsteadOfAnEmptySuccess() {
        when(memories.validItems(owner)).thenReturn(List.of(item(owner, "Redis 限流")));
        when(embeddings.isConfigured(owner)).thenReturn(true);
        when(embeddings.embedQuery(owner, "golang")).thenReturn(new EmbeddingVector(new float[]{1f, 0f}, "fixture"));
        when(vectors.similar(eq(owner), anyString(), anyInt(), any(), anyInt())).thenReturn(List.of());

        Search result = service.search(owner, "golang", null, null);

        assertThat(result.retrieval()).isEqualTo("no_results");
        assertThat(result.adopted()).isZero();
        assertThat(result.text()).contains("没有检索到相关长期记忆");
    }

    @Test
    void invalidQueriesFailTheirOwnSpan() {
        var tracer = mock(AgentRunTracer.class);
        UUID span = UUID.randomUUID();
        when(tracer.startChild(eq("CONTEXT"), eq("memory.search"), any())).thenReturn(span);

        assertThat(service.search(owner, "   ", 5, tracer).retrieval()).isEqualTo("invalid_query");
        verify(tracer).finishChild(eq(span), eq("FAILED"), eq("INVALID_QUERY"), any(), any(), any(), any());
    }

    @Test
    void searchRecordsCandidateAndAdoptedCountsOnItsOwnSpan() {
        var hit = item(owner, "投递节奏：每周 10 家");
        when(memories.validItems(owner)).thenReturn(List.of(hit));
        when(embeddings.isConfigured(owner)).thenReturn(false);
        var tracer = mock(AgentRunTracer.class);
        UUID span = UUID.randomUUID();
        when(tracer.startChild(eq("CONTEXT"), eq("memory.search"), any())).thenReturn(span);

        service.search(owner, "投递", null, tracer);

        var captured = ArgumentCaptor.forClass(Map.class);
        verify(tracer).finishChild(eq(span), eq("COMPLETED"), isNull(), any(), any(), any(), captured.capture());
        assertThat(captured.getValue())
                .containsEntry("candidateMemories", 1)
                .containsEntry("adoptedMemories", 1)
                .containsEntry("retrieval", "lexical_not_configured")
                .containsEntry("memoryContextTokens", "unknown");
        // The query text itself stays out of the trace.
        assertThat(captured.getValue()).doesNotContainKey("query");
    }

    @Test
    void residentLoadFailureIsRecordedAsFailedOnItsOwnSpan() {
        when(memories.validItems(owner)).thenThrow(new IllegalStateException("database down"));
        var tracer = mock(AgentRunTracer.class);
        UUID span = UUID.randomUUID();
        when(tracer.startChild(eq("CONTEXT"), eq("memory.context_load"), any())).thenReturn(span);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.context(owner, tracer))
                .isInstanceOf(IllegalStateException.class);
        verify(tracer).finishChild(eq(span), eq("FAILED"), eq("IllegalStateException"),
                any(), any(), any(), any());
    }

    @Test
    void aJustCorrectedMemoryStaysSearchableBeforeItIsEmbedded() {
        var indexed = item(owner, "Redis 限流用令牌桶");
        var fresh = item(owner, "改成偏好 Go 后端");
        when(memories.validItems(owner)).thenReturn(List.of(indexed, fresh));
        when(embeddings.isConfigured(owner)).thenReturn(true);
        when(embeddings.embedQuery(owner, "偏好")).thenReturn(new EmbeddingVector(new float[]{1f, 0f}, "fixture"));
        when(vectors.similar(eq(owner), anyString(), anyInt(), any(), anyInt()))
                .thenReturn(List.of(new MemoryVectorStore.Match(indexed.getId(), 0.8)));

        Search result = service.search(owner, "偏好", null, null);

        // The fresh row has no vector yet, so it arrives through the lexical path, not by luck.
        assertThat(result.retrieval()).isEqualTo("semantic");
        // The fresh row carries no similarity score because it arrived without a vector.
        assertThat(result.text()).contains("（记忆 id=" + fresh.getId() + "）")
                .contains("相关度 0.80");
        assertThat(result.adopted()).isEqualTo(2);
    }

    @Test
    void lexicalMatchingPrefersMoreMatchedTermsAndStaysWithinTheLimit() {
        var both = item(owner, "Redis 限流与超卖防护");
        var one = item(owner, "Redis 基础数据结构");
        var other = item(owner, "Kafka 分区策略");

        List<AgentMemoryItem> matches = MemoryEmbeddingService.lexicalMatches(
                List.of(one, both, other), "Redis 限流", 5);

        assertThat(matches).containsExactly(both, one);
        assertThat(MemoryEmbeddingService.lexicalMatches(List.of(other), "Redis 限流", 5)).isEmpty();
        assertThat(MemoryEmbeddingService.lexicalMatches(List.of(both, one), "Redis", 1)).hasSize(1);
    }

    @Test
    void queryTermsCoverLatinWordsAndCJKBigrams() {
        assertThat(MemoryEmbeddingService.queryTerms("Redis 限流")).contains("redis", "限流");
        assertThat(MemoryEmbeddingService.queryTerms("！！！")).isEmpty();
    }

    @Test
    void invalidatingAMemoryRemovesItsCoreFlag() {
        var core = pinned(owner, "目标岗位：后端开发");
        core.invalidate(null, null);
        assertThat(core.isPinned()).isFalse();
        assertThat(core.isValid()).isFalse();
    }

    @Test
    void serializesVectorsForPgvector() {
        assertThat(MemoryEmbeddingService.toLiteral(new float[]{1.5f, -2f})).isEqualTo("[1.5,-2.0]");
    }
}
