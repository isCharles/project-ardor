package com.projectardor.agent.service;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.projectardor.agent.repository.MemoryVectorStore;

/** Gradually embeds memories that were written before an embedding model was configured. */
@Component
public class MemoryEmbeddingScheduler {
    private static final Logger log = LoggerFactory.getLogger(MemoryEmbeddingScheduler.class);
    private final MemoryVectorStore vectorStore;
    private final MemoryEmbeddingService embeddingService;

    public MemoryEmbeddingScheduler(MemoryVectorStore vectorStore, MemoryEmbeddingService embeddingService) {
        this.vectorStore = vectorStore;
        this.embeddingService = embeddingService;
    }

    @Scheduled(initialDelayString = "${app.memory.embedding-backfill-initial-delay:15s}",
            fixedDelayString = "${app.memory.embedding-backfill-interval:120s}")
    public void backfillPendingEmbeddings() {
        for (UUID userId : vectorStore.usersWithPending(20)) {
            try {
                embeddingService.embedPending(userId);
            } catch (RuntimeException exception) {
                log.warn("Memory embedding pass deferred: userId={}, cause={}",
                        userId, exception.getClass().getSimpleName());
            }
        }
    }
}
