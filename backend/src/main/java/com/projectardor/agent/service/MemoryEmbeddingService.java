package com.projectardor.agent.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.projectardor.agent.domain.AgentMemoryItem;
import com.projectardor.agent.repository.MemoryVectorStore;
import com.projectardor.knowledge.service.KnowledgeEmbeddingService;
import com.projectardor.knowledge.service.KnowledgeEmbeddingService.EmbeddingVector;

/**
 * Retrieves the memories worth injecting for one user turn. With an embedding model the
 * ranking mixes similarity, importance and recency; without one the prompt keeps the
 * previous behaviour of including every valid memory.
 */
@Service
public class MemoryEmbeddingService {
    private static final Logger log = LoggerFactory.getLogger(MemoryEmbeddingService.class);
    private static final int MATCH_LIMIT = 24;
    private static final int CHARACTER_BUDGET = 2400;

    private final AgentMemoryService memoryService;
    private final MemoryVectorStore vectorStore;
    private final KnowledgeEmbeddingService embeddings;

    public MemoryEmbeddingService(AgentMemoryService memoryService, MemoryVectorStore vectorStore,
            KnowledgeEmbeddingService embeddings) {
        this.memoryService = memoryService;
        this.vectorStore = vectorStore;
        this.embeddings = embeddings;
    }

    /** Best-effort embedding backfill; a missing model simply leaves rows pending. */
    public void embedPending(UUID userId) {
        String model = embeddings.modelName(userId);
        if (model == null) return;
        var pending = vectorStore.pendingForUser(userId, model, 20);
        if (pending.isEmpty()) return;
        List<EmbeddingVector> vectors = embeddings.embedDocuments(userId,
                pending.stream().map(MemoryVectorStore.Pending::content).toList());
        for (int index = 0; index < pending.size(); index++) {
            EmbeddingVector vector = vectors.get(index);
            vectorStore.saveEmbedding(userId, pending.get(index).id(), pending.get(index).content(), toLiteral(vector.values()),
                    vector.values().length, vector.model());
        }
    }

    /** Prompt-ready memory section for this turn; empty when the user has no memories. */
    public String context(UUID userId, String userMessage) {
        List<AgentMemoryItem> items = memoryService.validItems(userId);
        if (items.isEmpty()) return "";
        if (userMessage != null && !userMessage.isBlank() && embeddings.isConfigured(userId)) {
            try {
                EmbeddingVector vector = embeddings.embedQuery(userId, userMessage);
                if (vector != null) {
                    var matches = vectorStore.similar(userId, toLiteral(vector.values()),
                            vector.values().length, vector.model(), MATCH_LIMIT);
                    if (!matches.isEmpty()) {
                        Map<UUID, Double> similarities = new LinkedHashMap<>();
                        matches.forEach(match -> similarities.put(match.id(), match.similarity()));
                        Instant now = Instant.now();
                        return render(items.stream()
                                .filter(item -> similarities.containsKey(item.getId()))
                                .sorted(Comparator.comparingDouble((AgentMemoryItem item) ->
                                        score(similarities.get(item.getId()), item.getImportance(),
                                                ageDays(item, now))).reversed())
                                .toList());
                    }
                }
            } catch (RuntimeException exception) {
                log.debug("Memory semantic retrieval unavailable: userId={}, type={}",
                        userId, exception.getClass().getSimpleName());
            }
        }
        Instant now = Instant.now();
        return render(items.stream()
                .sorted(Comparator.comparingDouble((AgentMemoryItem item) ->
                        score(0.5, item.getImportance(), ageDays(item, now))).reversed())
                .toList());
    }

    static double score(double similarity, int importance, double ageDays) {
        double clampedImportance = Math.max(0, Math.min(5, importance)) / 5.0;
        double recency = 1.0 / (1.0 + Math.max(0, ageDays) / 30.0);
        return 0.6 * similarity + 0.25 * clampedImportance + 0.15 * recency;
    }

    static String render(List<AgentMemoryItem> items) {
        StringBuilder out = new StringBuilder();
        for (AgentMemoryItem item : items) {
            String line = "- " + item.getContent() + "\n";
            if (out.length() + line.length() > CHARACTER_BUDGET) break;
            out.append(line);
        }
        return out.toString().strip();
    }

    private static double ageDays(AgentMemoryItem item, Instant now) {
        Instant since = item.getValidFrom() != null ? item.getValidFrom()
                : item.getCreatedAt() != null ? item.getCreatedAt() : now;
        return Math.max(0, Duration.between(since, now).toMinutes() / 1440.0);
    }

    static String toLiteral(float[] values) {
        StringBuilder literal = new StringBuilder("[");
        for (int index = 0; index < values.length; index++) {
            if (index > 0) literal.append(',');
            literal.append(values[index]);
        }
        return literal.append(']').toString();
    }
}
