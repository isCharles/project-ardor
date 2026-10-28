package com.projectardor.assistant.memory.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.projectardor.assistant.execution.service.AgentRunTracer;
import com.projectardor.assistant.memory.domain.AgentMemoryItem;
import com.projectardor.assistant.memory.repository.MemoryVectorStore;
import com.projectardor.materials.knowledge.service.KnowledgeEmbeddingService;
import com.projectardor.materials.knowledge.service.KnowledgeEmbeddingService.EmbeddingVector;

/**
 * Long-term memory reaches the model in two ways: pinned core facts stay resident in every
 * context, and the remaining facts are retrieved only when the agent asks for them. A turn never
 * embeds the user's message on its own, so an embedding call appears in the trace only when it was
 * a deliberate retrieval, and its failure stays visible even when the run continues.
 */
@Service
public class MemoryEmbeddingService {
    private static final Logger log = LoggerFactory.getLogger(MemoryEmbeddingService.class);
    private static final int RESIDENT_CHARACTER_BUDGET = 2400;
    private static final int CORE_CHARACTER_BUDGET = 1200;
    private static final int SEARCH_LIMIT = 8;
    private static final int SEARCH_LIMIT_CAP = 20;
    private static final int SEARCH_CHARACTER_BUDGET = 2000;
    private static final int CANDIDATE_LIMIT = 24;
    private static final Pattern QUERY_TERMS = Pattern.compile("[a-z0-9]{2,}|[\\u3400-\\u9fff]+");

    private final AgentMemoryService memoryService;
    private final MemoryVectorStore vectorStore;
    private final KnowledgeEmbeddingService embeddings;

    public MemoryEmbeddingService(AgentMemoryService memoryService, MemoryVectorStore vectorStore,
            KnowledgeEmbeddingService embeddings) {
        this.memoryService = memoryService;
        this.vectorStore = vectorStore;
        this.embeddings = embeddings;
    }

    /** Resident memory block plus the counts the trace needs; token usage is unknown until measured. */
    public record Context(String promptSection, String outcome, int candidates, int coreCandidates,
            int coreAdopted, int coreOmitted, int adopted, int omitted, int contextChars,
            int characterBudget, String contextTokens, int embeddingCalls) {

        /** The block a user with no stored memories gets; callers that only need the text use this. */
        public static Context none() {
            return new Context("", "no_memories", 0, 0, 0, 0, 0, 0, 0, RESIDENT_CHARACTER_BUDGET, "unknown", 0);
        }
    }

    /** One on-demand retrieval, including how it was answered so a fallback is never silent. */
    public record Search(String text, String retrieval, int candidates, int adopted, int omitted,
            int contextChars, String embeddingModel, String errorCode) {
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

    /** Prompt-ready resident memory. Never contacts the embedding provider. */
    public Context context(UUID userId, AgentRunTracer tracer) {
        UUID span = startSpan(tracer, "memory.context_load", Map.of());
        try {
            Context result = residentContext(memoryService.validItems(userId));
            var attributes = new LinkedHashMap<String, Object>();
            attributes.put("outcome", result.outcome());
            attributes.put("candidateMemories", result.candidates());
            attributes.put("coreCandidates", result.coreCandidates());
            attributes.put("coreAdopted", result.coreAdopted());
            attributes.put("coreOmitted", result.coreOmitted());
            attributes.put("adoptedMemories", result.adopted());
            attributes.put("omittedMemories", result.omitted());
            attributes.put("memoryContextChars", result.contextChars());
            attributes.put("memoryCharacterBudget", result.characterBudget());
            attributes.put("memoryContextTokens", result.contextTokens());
            attributes.put("embeddingCalls", result.embeddingCalls());
            finishSpan(tracer, span, "COMPLETED", null, attributes);
            return result;
        } catch (RuntimeException failure) {
            // The run may still continue, but this step did not succeed and must not look like it did.
            finishSpan(tracer, span, "FAILED", failure.getClass().getSimpleName(),
                    Map.of("outcome", "load_failed", "embeddingCalls", 0));
            throw failure;
        }
    }

    /** Retrieval the agent asked for: semantic when an embedding model is usable, lexical otherwise. */
    public Search search(UUID userId, String rawQuery, Integer requestedLimit, AgentRunTracer tracer) {
        String query = rawQuery == null ? "" : rawQuery.strip();
        int limit = requestedLimit == null || requestedLimit < 1 ? SEARCH_LIMIT
                : Math.min(requestedLimit, SEARCH_LIMIT_CAP);
        UUID span = startSpan(tracer, "memory.search", Map.of("queryChars", query.length(), "limit", limit));
        var attributes = new LinkedHashMap<String, Object>();
        attributes.put("queryChars", query.length());
        attributes.put("limit", limit);
        if (query.isBlank()) {
            Search empty = new Search("（检索长期记忆需要一个具体查询词。）", "invalid_query", 0, 0, 0, 0, null, null);
            finishSpan(tracer, span, "FAILED", "INVALID_QUERY", attributes);
            return empty;
        }
        List<AgentMemoryItem> items = memoryService.validItems(userId);
        attributes.put("candidateMemories", items.size());
        if (items.isEmpty()) {
            attributes.put("retrieval", "no_memories");
            finishSpan(tracer, span, "COMPLETED", null, attributes);
            return new Search("（当前没有可用的长期记忆。）", "no_memories", 0, 0, 0, 0, null, null);
        }
        String model = embeddings.modelName(userId);
        attributes.put("embeddingModel", model == null ? "" : model);
        String retrieval;
        String errorCode = null;
        List<Scored> matches = List.of();
        if (!embeddings.isConfigured(userId)) {
            retrieval = "lexical_not_configured";
            attributes.put("embeddingCalls", 0);
        } else {
            UUID embeddingSpan = startSpan(tracer, "memory.query_embedding",
                    Map.of("embeddingModel", model == null ? "" : model));
            long startedAt = System.nanoTime();
            try {
                EmbeddingVector vector = embeddings.embedQuery(userId, query);
                if (vector == null) {
                    finishSpan(tracer, embeddingSpan, "FAILED", "EMPTY_EMBEDDING", Map.of());
                    retrieval = "lexical_fallback";
                    errorCode = "EMPTY_EMBEDDING";
                } else {
                    var similarities = new LinkedHashMap<UUID, Double>();
                    vectorStore.similar(userId, toLiteral(vector.values()), vector.values().length, vector.model(),
                            CANDIDATE_LIMIT).forEach(match -> similarities.put(match.id(), match.similarity()));
                    matches = items.stream().filter(item -> similarities.containsKey(item.getId()))
                            .map(item -> new Scored(item, similarities.get(item.getId()), true))
                            .sorted(Comparator.comparingDouble((Scored match) -> match.similarity()).reversed())
                            .limit(limit).toList();
                    attributes.put("embeddingDimensions", vector.values().length);
                    attributes.put("vectorMatches", similarities.size());
                    finishSpan(tracer, embeddingSpan, "COMPLETED", null, Map.of(
                            "dimensions", vector.values().length,
                            "durationMs", elapsedMs(startedAt),
                            "embeddingModel", vector.model() == null ? "" : vector.model()));
                    retrieval = matches.isEmpty() ? "no_results" : "semantic";
                }
            } catch (RuntimeException failure) {
                errorCode = failure.getClass().getSimpleName();
                finishSpan(tracer, embeddingSpan, "FAILED", errorCode, Map.of("durationMs", elapsedMs(startedAt)));
                log.warn("Memory query embedding failed, falling back to lexical matching: userId={}, failure={}",
                        userId, errorCode);
                retrieval = "lexical_fallback";
            }
            attributes.put("embeddingCalls", 1);
        }
        // Fresh rows may still lack a vector, and lexical hits can add what cosine distance missed.
        var merged = new java.util.ArrayList<>(matches);
        var adoptedIds = new java.util.LinkedHashSet<UUID>();
        merged.forEach(match -> adoptedIds.add(match.item().getId()));
        List<AgentMemoryItem> lexical = lexicalMatches(items, query, limit);
        int supplements = 0;
        for (AgentMemoryItem item : lexical) {
            if (adoptedIds.contains(item.getId())) continue;
            if (merged.size() >= limit) break;
            merged.add(new Scored(item, 0, false));
            adoptedIds.add(item.getId());
            supplements++;
        }
        attributes.put("lexicalMatches", lexical.size());
        attributes.put("lexicalSupplements", supplements);
        if (merged.isEmpty()) {
            retrieval = "no_results";
        } else if (!retrieval.startsWith("lexical") && matches.isEmpty()) {
            retrieval = "lexical_supplement";
        }
        matches = merged;
        attributes.put("retrieval", retrieval);
        Search result = renderSearch(matches, items.size(), retrieval, errorCode, model);
        attributes.put("adoptedMemories", result.adopted());
        attributes.put("omittedMemories", result.omitted());
        attributes.put("memoryContextChars", result.contextChars());
        attributes.put("memoryContextTokens", "unknown");
        finishSpan(tracer, span, "invalid_query".equals(retrieval) ? "FAILED" : "COMPLETED", null, attributes);
        return result;
    }

    private static Search renderSearch(List<Scored> matches, int candidates, String retrieval, String errorCode,
            String model) {
        if (matches.isEmpty()) {
            return new Search("（没有检索到相关长期记忆，可换查询词重试或用 list_memories 逐条读取。）", retrieval,
                    candidates, 0, 0, 0, model, errorCode);
        }
        StringBuilder out = new StringBuilder();
        int adopted = 0;
        int omitted = 0;
        for (Scored match : matches) {
            String line = "- " + match.item().getContent() + "（记忆 id=" + match.item().getId()
                    + (match.semantic() ? String.format(Locale.ROOT, "，相关度 %.2f", match.similarity()) : "")
                    + "）\n";
            if (out.length() + line.length() > SEARCH_CHARACTER_BUDGET) {
                omitted++;
                continue;
            }
            out.append(line);
            adopted++;
        }
        if (omitted > 0) {
            out.append("（另有 ").append(omitted).append(" 条命中因记忆预算未展开。）\n");
        }
        return new Search(out.toString().strip(), retrieval, candidates, adopted, omitted, out.length(), model, errorCode);
    }

    private static Context residentContext(List<AgentMemoryItem> items) {
        if (items.isEmpty()) {
            return new Context("", "no_memories", 0, 0, 0, 0, 0, 0, 0, RESIDENT_CHARACTER_BUDGET, "unknown", 0);
        }
        Comparator<AgentMemoryItem> oldest = Comparator.comparing(AgentMemoryItem::getCreatedAt,
                Comparator.nullsLast(Comparator.naturalOrder()));
        List<AgentMemoryItem> core = items.stream().filter(AgentMemoryItem::isPinned).sorted(oldest).toList();
        List<AgentMemoryItem> rest = items.stream().filter(item -> !item.isPinned())
                .sorted(oldest.reversed()).toList();
        StringBuilder out = new StringBuilder();
        int coreAdopted = 0;
        int coreOmitted = 0;
        for (AgentMemoryItem item : core) {
            String line = bullet(item);
            if (out.length() + line.length() > CORE_CHARACTER_BUDGET) {
                coreOmitted++;
                continue;
            }
            out.append(line);
            coreAdopted++;
        }
        int adopted = coreAdopted;
        int omitted = coreOmitted;
        for (AgentMemoryItem item : rest) {
            String line = bullet(item);
            if (out.length() + line.length() > RESIDENT_CHARACTER_BUDGET) {
                omitted++;
                continue;
            }
            out.append(line);
            adopted++;
        }
        if (coreOmitted > 0) {
            out.append("（有 ").append(coreOmitted).append(" 条核心记忆超出常驻预算，未写入本轮上下文。）\n");
        }
        if (omitted - coreOmitted > 0) {
            out.append("（另有 ").append(omitted - coreOmitted)
                    .append(" 条长期记忆未展开；需要更早的目标、偏好或背景时调用 search_memories。）\n");
        }
        String outcome = omitted > 0 ? "budget_truncated" : "resident";
        return new Context(out.toString().strip(), outcome, items.size(), core.size(), coreAdopted, coreOmitted,
                adopted, omitted, out.length(), RESIDENT_CHARACTER_BUDGET, "unknown", 0);
    }

    private static String bullet(AgentMemoryItem item) {
        return "- " + item.getContent() + "\n";
    }

    private record Scored(AgentMemoryItem item, double similarity, boolean semantic) {}

    /** Substring matching over the user's own memories; used when no usable vector search exists. */
    static List<AgentMemoryItem> lexicalMatches(List<AgentMemoryItem> items, String query, int limit) {
        Set<String> terms = queryTerms(query);
        if (terms.isEmpty()) return List.of();
        Instant now = Instant.now();
        record Hit(AgentMemoryItem item, double ratio, double recency) {}
        return items.stream()
                .map(item -> {
                    String haystack = item.getContent().toLowerCase(Locale.ROOT);
                    long hits = terms.stream().filter(haystack::contains).count();
                    return new Hit(item, hits / (double) terms.size(), recency(item, now));
                })
                .filter(hit -> hit.ratio() > 0)
                .sorted(Comparator.comparingDouble(Hit::ratio).reversed()
                        .thenComparing(Comparator.comparingDouble(Hit::recency).reversed()))
                .limit(limit)
                .map(Hit::item)
                .toList();
    }

    static Set<String> queryTerms(String query) {
        var terms = new LinkedHashSet<String>();
        var matcher = QUERY_TERMS.matcher(query.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String token = matcher.group();
            if (token.length() == 1) {
                terms.add(token);
                continue;
            }
            terms.add(token);
            for (int index = 0; index + 1 < token.length(); index++) {
                terms.add(token.substring(index, index + 2));
            }
        }
        return terms;
    }

    private static double recency(AgentMemoryItem item, Instant now) {
        Instant since = item.getValidFrom() != null ? item.getValidFrom()
                : item.getCreatedAt() != null ? item.getCreatedAt() : now;
        return Math.max(0, Duration.between(since, now).toMinutes() / 1440.0);
    }

    private static long elapsedMs(long startedAtNanos) {
        return Math.max(0, (System.nanoTime() - startedAtNanos) / 1_000_000);
    }

    private static UUID startSpan(AgentRunTracer tracer, String name, Map<String, Object> attributes) {
        if (tracer == null) return null;
        UUID parent = tracer.contextSpanId();
        return parent == null ? tracer.startChild("CONTEXT", name, attributes)
                : tracer.startChild(parent, "CONTEXT", name, attributes);
    }

    private static void finishSpan(AgentRunTracer tracer, UUID span, String status, String errorCode,
            Map<String, Object> attributes) {
        if (tracer == null || span == null) return;
        tracer.finishChild(span, status, errorCode, null, null, null, attributes);
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
