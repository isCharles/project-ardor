package com.projectardor.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import com.projectardor.knowledge.domain.KnowledgeChunk;
import com.projectardor.knowledge.domain.KnowledgeDocument;
import com.projectardor.knowledge.repository.KnowledgeChunkRepository;
import com.projectardor.knowledge.repository.KnowledgeDocumentRepository;
import com.projectardor.websearch.service.TavilySearchService;
import com.projectardor.websearch.service.WebSearchResult;

class KnowledgeServiceTests {
    private KnowledgeDocumentRepository documentRepository;
    private KnowledgeChunkRepository chunkRepository;
    private TavilySearchService tavilySearchService;
    private KnowledgeService service;

    @BeforeEach
    void setUp() {
        documentRepository = mock(KnowledgeDocumentRepository.class);
        chunkRepository = mock(KnowledgeChunkRepository.class);
        tavilySearchService = mock(TavilySearchService.class);
        when(documentRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service = new KnowledgeService(
                documentRepository, chunkRepository, new KnowledgeTextExtractor(), tavilySearchService);
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
        KnowledgeDocument css = KnowledgeDocument.fromWeb(
                userId, "CSS 布局", "https://example.com/css", "网格布局与响应式设计");
        when(documentRepository.findAllByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(java, css));
        when(chunkRepository.findAllByUserId(userId)).thenReturn(List.of(
                KnowledgeChunk.create(userId, css.getId(), 1, "CSS 网格布局"),
                KnowledgeChunk.create(userId, java.getId(), 1, "Java 虚拟线程可以降低阻塞 I/O 的线程成本")));

        var results = service.search(userId, "Java 虚拟线程", 5);

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().documentId()).isEqualTo(java.getId());
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
