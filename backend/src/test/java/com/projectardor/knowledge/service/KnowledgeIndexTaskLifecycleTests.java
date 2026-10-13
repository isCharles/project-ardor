package com.projectardor.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.projectardor.knowledge.repository.KnowledgeVectorStore;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronization;

class KnowledgeIndexTaskLifecycleTests {
    private final UUID user = UUID.randomUUID(), chunk = UUID.randomUUID();
    private final KnowledgeEmbeddingService embedding = mock(KnowledgeEmbeddingService.class);
    private final KnowledgeVectorStore vectors = mock(KnowledgeVectorStore.class);
    private final KnowledgeIndexTaskStore tasks = mock(KnowledgeIndexTaskStore.class);
    private final KnowledgeIndexer indexer = new KnowledgeIndexer(embedding,vectors,tasks);
    private final List<KnowledgeIndexTaskStore.Run> runs = List.of(new KnowledgeIndexTaskStore.Run(
            UUID.randomUUID(),user,UUID.randomUUID(),UUID.randomUUID(),List.of(chunk)));

    @Test void missingConfigurationRecordsFailureWithoutDiscardingChunks() {
        when(tasks.begin(user,List.of(chunk))).thenReturn(runs);
        assertThat(indexer.indexNewChunks(user,List.of(chunk),List.of("original content"))).isZero();
        verify(tasks).finish(runs,"EMBEDDING_NOT_CONFIGURED");
        verifyNoInteractions(vectors);
    }
    @Test void configurationFailureDoesNotLeaveTheTaskRunning() {
        when(tasks.begin(user,List.of(chunk))).thenReturn(runs);
        when(embedding.isConfigured(user)).thenThrow(new IllegalArgumentException("provider unavailable"));
        assertThat(indexer.indexNewChunks(user,List.of(chunk),List.of("original content"))).isZero();
        verify(tasks).finish(runs,"EMBEDDING_FAILED");
    }
    @Test void aDocumentAlreadyClaimedByAnotherWorkerDoesNotCallTheProvider() {
        when(tasks.begin(user,List.of(chunk))).thenReturn(List.of());
        assertThat(indexer.indexNewChunks(user,List.of(chunk),List.of("original content"))).isZero();
        verifyNoInteractions(embedding,vectors);
    }
    @Test void completionIsPublishedOnlyAfterEmbeddingTransactionCommits() {
        when(tasks.begin(user,List.of(chunk))).thenReturn(runs);
        when(embedding.isConfigured(user)).thenReturn(true);
        when(embedding.embedDocuments(user,List.of("original content"))).thenReturn(List.of(
                new KnowledgeEmbeddingService.EmbeddingVector(new float[] {1.0f,0.0f},"test-model")));
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThat(indexer.indexNewChunks(user,List.of(chunk),List.of("original content"))).isEqualTo(1);
            verify(tasks,never()).finish(runs,null);
            TransactionSynchronizationManager.getSynchronizations().forEach(callback -> callback.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
            verify(tasks).finish(runs,null);
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
    }
}
