package com.projectardor.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import com.projectardor.knowledge.domain.KnowledgeDocument;
import com.projectardor.knowledge.repository.KnowledgeChunkRepository;
import com.projectardor.knowledge.repository.KnowledgeDocumentRepository;
import com.projectardor.knowledge.repository.KnowledgeVectorStore;
import com.projectardor.websearch.service.TavilySearchService;
import com.projectardor.websearch.service.WebSearchResult;

class KnowledgeServiceTests {
    private KnowledgeDocumentRepository documentRepository;
    private KnowledgeChunkRepository chunkRepository;
    private TavilySearchService tavilySearchService;
    private KnowledgeEmbeddingService embeddingService;
    private KnowledgeVectorStore vectorStore;
    private KnowledgeIndexer indexer;
    private KnowledgeService service;

    @BeforeEach
    void setUp() {
        documentRepository = mock(KnowledgeDocumentRepository.class);
        chunkRepository = mock(KnowledgeChunkRepository.class);
        tavilySearchService = mock(TavilySearchService.class);
        embeddingService = mock(KnowledgeEmbeddingService.class);
        vectorStore = mock(KnowledgeVectorStore.class);
        indexer = mock(KnowledgeIndexer.class);
        var transactionManager = mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenReturn(mock(org.springframework.transaction.TransactionStatus.class));
        when(documentRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service = new KnowledgeService(
                documentRepository, chunkRepository, new KnowledgeTextExtractor(), tavilySearchService,
                embeddingService, vectorStore, indexer, 0.35, transactionManager);
    }

    @Test
    void uploadSplitsLongTextIntoRetrievableChunks() {
        UUID userId = UUID.randomUUID();
        String content = "Java 虚拟线程适合 I/O 密集任务。\n".repeat(120);
        MockMultipartFile file = new MockMultipartFile(
                "file", "virtual-threads.md", "text/markdown", content.getBytes(StandardCharsets.UTF_8));

        var response = service.upload(userId, file);

        assertThat(response.title()).isEqualTo("virtual-threads");
        assertThat(response.chunkCount()).isGreaterThan(1);
    }

    @Test
    void searchReturnsOnlyRelevantChunksInScoreOrder() {
        UUID userId = UUID.randomUUID();
        KnowledgeDocument java = KnowledgeDocument.fromWeb(
                userId, "Java 虚拟线程", "https://example.com/java", "虚拟线程适合高并发 I/O");
        when(vectorStore.lexicalSearch(eq(userId), any(), eq(30))).thenReturn(List.of(
                new KnowledgeVectorStore.LexicalHit(java.getId(), java.getTitle(), java.getSourceType(),
                        java.getSourceUrl(), "Java 虚拟线程可以降低阻塞 I/O 的线程成本", 12.0)));

        var results = service.search(userId, "Java 虚拟线程", 5);

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().documentId()).isEqualTo(java.getId());
    }

    @Test
    void semanticSearchFindsAParaphraseThatShareNoCharactersWithTheQuery() {
        UUID userId = UUID.randomUUID();
        KnowledgeDocument gc = KnowledgeDocument.fromWeb(
                userId, "JVM 笔记", "https://example.com/gc", "对象优先在 Eden 区分配，晋升到老年代");
        when(documentRepository.findAllByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(gc));
        // "自动内存管理" shares no characters with the stored text, so the lexical
        // retriever returns nothing; only the dense retriever can find it.
        when(embeddingService.embedQuery(userId, "自动内存管理"))
                .thenReturn(new KnowledgeEmbeddingService.EmbeddingVector(new float[] {1f, 0f}, "stub"));
        when(vectorStore.search(any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(new KnowledgeVectorStore.VectorHit(
                        UUID.randomUUID(), gc.getId(), "对象优先在 Eden 区分配，晋升到老年代", 0.91)));

        var results = service.search(userId, "自动内存管理", 5);

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().documentId()).isEqualTo(gc.getId());
    }

    @Test
    void searchStillWorksWhenNoEmbeddingModelIsConfigured() {
        UUID userId = UUID.randomUUID();
        KnowledgeDocument java = KnowledgeDocument.fromWeb(
                userId, "Java 虚拟线程", "https://example.com/java", "虚拟线程适合高并发 I/O");
        when(documentRepository.findAllByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(java));
        when(vectorStore.lexicalSearch(eq(userId), any(), eq(30))).thenReturn(List.of(
                new KnowledgeVectorStore.LexicalHit(java.getId(), java.getTitle(), java.getSourceType(),
                        java.getSourceUrl(), "Java 虚拟线程降低阻塞 I/O 成本", 8.0)));
        // No embedding model configured: embedQuery returns null and dense recall is skipped.
        when(embeddingService.embedQuery(any(), any())).thenReturn(null);

        var results = service.search(userId, "虚拟线程", 5);

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().documentId()).isEqualTo(java.getId());
    }

    @Test
    void aChunkFoundByBothRetrieversOutranksOneFoundByOnlyOne() {
        UUID userId = UUID.randomUUID();
        KnowledgeDocument both = KnowledgeDocument.fromWeb(
                userId, "命中两路", "https://example.com/both", "虚拟线程");
        KnowledgeDocument denseOnly = KnowledgeDocument.fromWeb(
                userId, "只命中向量", "https://example.com/dense", "协程");
        when(documentRepository.findAllByUserIdOrderByCreatedAtDesc(userId))
                .thenReturn(List.of(both, denseOnly));
        when(vectorStore.lexicalSearch(eq(userId), any(), eq(30))).thenReturn(List.of(
                new KnowledgeVectorStore.LexicalHit(both.getId(), both.getTitle(), both.getSourceType(),
                        both.getSourceUrl(), "虚拟线程", 8.0)));
        when(embeddingService.embedQuery(any(), any()))
                .thenReturn(new KnowledgeEmbeddingService.EmbeddingVector(new float[] {1f, 0f}, "stub"));
        // The dense retriever ranks the semantic-only document first; fusion must still
        // promote the chunk that both retrievers agree on.
        when(vectorStore.search(any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(
                        new KnowledgeVectorStore.VectorHit(UUID.randomUUID(), denseOnly.getId(), "协程", 0.95),
                        new KnowledgeVectorStore.VectorHit(UUID.randomUUID(), both.getId(), "虚拟线程", 0.80)));

        var results = service.search(userId, "虚拟线程", 5);

        assertThat(results.getFirst().documentId()).isEqualTo(both.getId());
    }

    @Test
    void aDistantNearestNeighbourIsNotReportedAsAMatch() {
        UUID userId = UUID.randomUUID();
        KnowledgeDocument jvm = KnowledgeDocument.fromWeb(
                userId, "JVM 笔记", "https://example.com/gc", "对象优先在 Eden 区分配");
        when(documentRepository.findAllByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(jvm));
        when(embeddingService.embedQuery(any(), any()))
                .thenReturn(new KnowledgeEmbeddingService.EmbeddingVector(new float[] {1f, 0f}, "stub"));
        // The only document in the library is the nearest neighbour by definition, but it
        // is far away. An unrelated question must come back empty rather than be handed
        // irrelevant context.
        when(vectorStore.search(any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(new KnowledgeVectorStore.VectorHit(
                        UUID.randomUUID(), jvm.getId(), "对象优先在 Eden 区分配", 0.08)));

        assertThat(service.search(userId, "数据库索引优化", 5)).isEmpty();
    }

    @Test
    void agentResearchPersistsSourcesReturnedByWebSearch() {
        UUID userId = UUID.randomUUID();
        when(tavilySearchService.search(userId, "JVM GC")).thenReturn(new WebSearchResult(
                "JVM GC",
                List.of(new WebSearchResult.ResultItem(
                        "JVM GC 指南", "https://example.com/gc", "分代回收与垃圾收集器概览", 0.9)),
                1,
                "0.2"));
        when(documentRepository.findByUserIdAndSourceUrl(userId, "https://example.com/gc"))
                .thenReturn(Optional.empty());

        var response = service.researchFromWeb(userId, "JVM GC");

        assertThat(response.imported()).hasSize(1);
        assertThat(response.imported().getFirst().sourceUrl()).isEqualTo("https://example.com/gc");
    }
}
