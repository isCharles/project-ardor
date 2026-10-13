package com.projectardor.knowledge.service;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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
    private final KnowledgeIndexTaskStore taskStore;
    private final KnowledgeEmbeddingWriter writer;

    public KnowledgeIndexer(KnowledgeEmbeddingService embeddingService, KnowledgeVectorStore vectorStore, KnowledgeIndexTaskStore taskStore,KnowledgeEmbeddingWriter writer) {
        this.embeddingService = embeddingService;
        this.vectorStore = vectorStore;
        this.taskStore = taskStore;
        this.writer=writer;
    }

    /**
     * Lease, model call, atomic vector write and completion are sequential boundaries.
     * No database connection is held while waiting for the embedding provider.
     */
    public int indexNewChunks(UUID userId, List<UUID> chunkIds, List<String> contents) {
        if (chunkIds.isEmpty()) {
            return 0;
        }
        var runs = taskStore.begin(userId,chunkIds);
        if (runs.isEmpty()) return 0;
        var claimed = runs.stream().flatMap(run -> run.chunks().stream()).collect(java.util.stream.Collectors.toSet());
        var indexes = java.util.stream.IntStream.range(0,chunkIds.size()).filter(index -> claimed.contains(chunkIds.get(index))).boxed().toList();
        try {
            if (!embeddingService.isConfigured(userId)) {
                taskStore.finish(runs,"EMBEDDING_NOT_CONFIGURED");
                return 0;
            }
            List<EmbeddingVector> vectors = embeddingService.embedDocuments(userId, indexes.stream().map(contents::get).toList());
            writer.write(userId,indexes.stream().map(chunkIds::get).toList(),vectors);
            taskStore.finish(runs,null);
            return indexes.size();
        } catch (RuntimeException exception) {
            taskStore.finish(runs,"EMBEDDING_FAILED");
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
    public int backfill(UUID userId, int limit) {
        List<PendingChunk> pending = vectorStore.findPending(userId, Math.min(limit, BACKFILL_BATCH));
        if (pending.isEmpty()) {
            return 0;
        }
        var runs = taskStore.begin(userId,pending.stream().map(PendingChunk::chunkId).toList());
        if (runs.isEmpty()) return 0;
        var claimed = runs.stream().flatMap(run -> run.chunks().stream()).collect(java.util.stream.Collectors.toSet());
        pending = pending.stream().filter(chunk -> claimed.contains(chunk.chunkId())).toList();
        try {
            if (!embeddingService.isConfigured(userId)) {
                taskStore.finish(runs,"EMBEDDING_NOT_CONFIGURED");
                return 0;
            }
            List<EmbeddingVector> vectors = embeddingService.embedDocuments(
                    userId, pending.stream().map(PendingChunk::content).toList());
            writer.write(userId,pending.stream().map(PendingChunk::chunkId).toList(),vectors);
            log.info("Backfilled {} knowledge embedding(s) for userId={}", pending.size(), userId);
            taskStore.finish(runs,null);
            return pending.size();
        } catch (RuntimeException exception) {
            taskStore.finish(runs,"EMBEDDING_FAILED");
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
