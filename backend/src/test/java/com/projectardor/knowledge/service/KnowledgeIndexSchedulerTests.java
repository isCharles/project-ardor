package com.projectardor.knowledge.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.projectardor.knowledge.repository.KnowledgeVectorStore;

class KnowledgeIndexSchedulerTests {

    @Test
    void backfillsEachUserWithPendingChunks() {
        KnowledgeVectorStore vectorStore = mock(KnowledgeVectorStore.class);
        KnowledgeIndexer indexer = mock(KnowledgeIndexer.class);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(vectorStore.findUsersWithPendingChunks(10)).thenReturn(List.of(first, second));

        new KnowledgeIndexScheduler(vectorStore, indexer).backfillPendingEmbeddings();

        verify(indexer).reconcileModel(first);
        verify(indexer).backfill(first, 64);
        verify(indexer).reconcileModel(second);
        verify(indexer).backfill(second, 64);
    }

    @Test
    void oneUsersFailureDoesNotBlockTheNextUser() {
        KnowledgeVectorStore vectorStore = mock(KnowledgeVectorStore.class);
        KnowledgeIndexer indexer = mock(KnowledgeIndexer.class);
        UUID failing = UUID.randomUUID();
        UUID healthy = UUID.randomUUID();
        when(vectorStore.findUsersWithPendingChunks(10)).thenReturn(List.of(failing, healthy));
        doThrow(new IllegalArgumentException("temporary DNS failure"))
                .when(indexer).reconcileModel(failing);

        new KnowledgeIndexScheduler(vectorStore, indexer).backfillPendingEmbeddings();

        verify(indexer).reconcileModel(healthy);
        verify(indexer).backfill(healthy, 64);
    }
}
