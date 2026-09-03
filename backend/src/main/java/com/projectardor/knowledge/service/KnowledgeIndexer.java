package com.projectardor.knowledge.service;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.projectardor.knowledge.repository.KnowledgeVectorStore;
import com.projectardor.knowledge.repository.KnowledgeVectorStore.PendingChunk;
import com.projectardor.knowledge.service.KnowledgeEmbeddingService.EmbeddingVector;

/**
 * Writes embeddings for knowledge chunks.
 *
 * <p>Embedding is deliberately best-effort at ingest time: a document must still be
 * usable (and keyword-searchable) when the user has not configured an embedding model
 * or the provider is briefly down. Anything left unembedded is picked up later by
 * {@link #backfill(UUID, int)}.
 */
@Service
public class KnowledgeIndexer {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexer.class);

    /** Caps one backfill pass so a large library cannot monopolise a request. */
    private static final int BACKFILL_BATCH = 64;

    private final KnowledgeEmbeddingService embeddingService;
    private final KnowledgeVectorStore vectorStore;

    public KnowledgeIndexer(KnowledgeEmbeddingService embeddingService, KnowledgeVectorStore vectorStore) {
        this.embeddingService = embeddingService;
        this.vectorStore = vectorStore;
    }

    /**
     * Embeds freshly ingested chunks. Runs in its own transaction so that an embedding
     * failure can never roll back the document the user just uploaded.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int indexNewChunks(UUID userId, List<UUID> chunkIds, List<String> contents) {
        if (chunkIds.isEmpty() || !embeddingService.isConfigured(userId)) {
            return 0;
        }
        try {
            List<EmbeddingVector> vectors = embeddingService.embedDocuments(userId, contents);
            for (int index = 0; index < chunkIds.size(); index++) {
                EmbeddingVector vector = vectors.get(index);
                vectorStore.saveEmbedding(
                        userId, chunkIds.get(index),
                        vector.toPgVectorLiteral(), vector.dimension(), vector.model());
            }
            return chunkIds.size();
        } catch (RuntimeException exception) {
            // The chunks stay searchable by keyword and remain queued for backfill.
            log.warn("Embedding new chunks failed, left pending for backfill: userId={}, chunks={}, cause={}",
                    userId, chunkIds.size(), exception.getClass().getSimpleName());
            return 0;
        }
    }

    /**
     * Embeds up to {@code limit} of this user's still-unembedded chunks. Called before a
     * semantic search so that documents ingested while no embedding model was configured
     * become retrievable as soon as one is.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int backfill(UUID userId, int limit) {
        if (!embeddingService.isConfigured(userId)) {
            return 0;
        }
        List<PendingChunk> pending = vectorStore.findPending(userId, Math.min(limit, BACKFILL_BATCH));
        if (pending.isEmpty()) {
            return 0;
        }
        try {
            List<EmbeddingVector> vectors = embeddingService.embedDocuments(
                    userId, pending.stream().map(PendingChunk::content).toList());
            for (int index = 0; index < pending.size(); index++) {
                EmbeddingVector vector = vectors.get(index);
                vectorStore.saveEmbedding(
                        userId, pending.get(index).chunkId(),
                        vector.toPgVectorLiteral(), vector.dimension(), vector.model());
            }
            log.info("Backfilled {} knowledge embedding(s) for userId={}", pending.size(), userId);
            return pending.size();
        } catch (RuntimeException exception) {
            log.warn("Knowledge embedding backfill failed: userId={}, cause={}",
                    userId, exception.getClass().getSimpleName());
            return 0;
        }
    }

    /**
     * Invalidates embeddings produced by a model other than the one now configured.
     * Mixing vector spaces would make cosine distance meaningless, so the affected rows
     * are reset and re-embedded by the normal backfill path.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int reconcileModel(UUID userId) {
        String model = embeddingService.modelName(userId);
        if (model == null) {
            return 0;
        }
        int cleared = vectorStore.clearEmbeddingsForOtherModels(userId, model);
        if (cleared > 0) {
            log.info("Embedding model changed for userId={}, invalidated {} vector(s)", userId, cleared);
        }
        return cleared;
    }
}
