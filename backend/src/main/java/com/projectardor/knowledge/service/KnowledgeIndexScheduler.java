package com.projectardor.knowledge.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.projectardor.knowledge.repository.KnowledgeVectorStore;

/**
 * Gradually fills embeddings for documents that predate the user's vector-model
 * configuration or were left pending during a temporary provider outage.
 */
@Component
public class KnowledgeIndexScheduler {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexScheduler.class);
    private static final int USERS_PER_PASS = 10;
    private static final int CHUNKS_PER_USER = 64;

    private final KnowledgeVectorStore vectorStore;
    private final KnowledgeIndexer indexer;

    public KnowledgeIndexScheduler(KnowledgeVectorStore vectorStore, KnowledgeIndexer indexer) {
        this.vectorStore = vectorStore;
        this.indexer = indexer;
    }

    @Scheduled(
            initialDelayString = "${app.knowledge.embedding-backfill-initial-delay:10s}",
            fixedDelayString = "${app.knowledge.embedding-backfill-interval:60s}")
    public void backfillPendingEmbeddings() {
        for (var userId : vectorStore.findUsersWithPendingChunks(USERS_PER_PASS)) {
            try {
                indexer.reconcileModel(userId);
                indexer.backfill(userId, CHUNKS_PER_USER);
            } catch (RuntimeException exception) {
                // A transient DNS/provider/configuration failure for one account must not
                // abort the rest of the pass. Its chunks remain pending for the next run.
                log.warn("Knowledge embedding pass deferred: userId={}, cause={}",
                        userId, exception.getClass().getSimpleName());
            }
        }
    }
}
