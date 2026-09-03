package com.projectardor.knowledge.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.projectardor.common.web.ResourceNotFoundException;
import com.projectardor.knowledge.domain.KnowledgeChunk;
import com.projectardor.knowledge.domain.KnowledgeDocument;
import com.projectardor.knowledge.repository.KnowledgeChunkRepository;
import com.projectardor.knowledge.repository.KnowledgeDocumentRepository;
import com.projectardor.knowledge.repository.KnowledgeVectorStore;
import com.projectardor.knowledge.web.KnowledgeDocumentResponse;
import com.projectardor.knowledge.web.KnowledgeResearchResponse;
import com.projectardor.knowledge.web.KnowledgeSearchResult;
import com.projectardor.websearch.service.TavilySearchService;
import com.projectardor.websearch.service.WebSearchResult;

@Service
public class KnowledgeService {
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;
    private static final int CHUNK_SIZE = 1_200;
    private static final int CHUNK_OVERLAP = 160;

    /** Reciprocal-rank-fusion damping. 60 is the value from the original RRF paper. */
    private static final int RRF_K = 60;

    /** How deep each retriever goes before fusion, so fusion has room to reorder. */
    private static final int CANDIDATE_DEPTH = 30;

    private final KnowledgeDocumentRepository documentRepository;
    private final KnowledgeChunkRepository chunkRepository;
    private final KnowledgeTextExtractor textExtractor;
    private final TavilySearchService tavilySearchService;
    private final KnowledgeEmbeddingService embeddingService;
    private final KnowledgeVectorStore vectorStore;
    private final KnowledgeIndexer indexer;
    private final double minSimilarity;

    public KnowledgeService(
            KnowledgeDocumentRepository documentRepository,
            KnowledgeChunkRepository chunkRepository,
            KnowledgeTextExtractor textExtractor,
            TavilySearchService tavilySearchService,
            KnowledgeEmbeddingService embeddingService,
            KnowledgeVectorStore vectorStore,
            KnowledgeIndexer indexer,
            @org.springframework.beans.factory.annotation.Value(
                    "${app.knowledge.min-similarity:0.35}") double minSimilarity) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.textExtractor = textExtractor;
        this.tavilySearchService = tavilySearchService;
        this.embeddingService = embeddingService;
        this.vectorStore = vectorStore;
        this.indexer = indexer;
        this.minSimilarity = minSimilarity;
    }

    @Transactional
    public KnowledgeDocumentResponse upload(UUID userId, MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("请选择知识文档");
        if (file.getSize() > MAX_FILE_SIZE) throw new IllegalArgumentException("知识文档不能超过 10MB");
        String filename = safeFilename(file.getOriginalFilename());
        String extension = extension(filename);
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception exception) {
            throw new IllegalArgumentException("无法读取知识文档", exception);
        }
        validateSignature(bytes, extension);
        String content = textExtractor.extract(bytes, extension);
        KnowledgeDocument document = documentRepository.saveAndFlush(KnowledgeDocument.uploaded(
                userId, titleFromFilename(filename), filename, contentType(extension), content));
        int chunkCount = storeChunks(document);
        return KnowledgeDocumentResponse.from(document, chunkCount);
    }

    @Transactional
    public KnowledgeResearchResponse researchFromWeb(UUID userId, String rawQuery) {
        String query = normalizeQuery(rawQuery);
        WebSearchResult search = tavilySearchService.search(userId, query);
        List<KnowledgeDocumentResponse> imported = new ArrayList<>();
        for (WebSearchResult.ResultItem result : search.results()) {
            if (result.url() == null || result.url().isBlank() || result.content() == null || result.content().isBlank()) {
                continue;
            }
            KnowledgeDocument document = documentRepository.findByUserIdAndSourceUrl(userId, result.url()).orElse(null);
            if (document == null) {
                String title = normalizeTitle(result.title(), result.url());
                document = documentRepository.saveAndFlush(
                        KnowledgeDocument.fromWeb(userId, title, result.url(), result.content().strip()));
                int chunkCount = storeChunks(document);
                imported.add(KnowledgeDocumentResponse.from(document, chunkCount));
            } else {
                imported.add(toResponse(userId, document));
            }
        }
        if (imported.isEmpty()) throw new IllegalStateException("联网搜索没有返回可保存的正文摘要");
        return new KnowledgeResearchResponse(query, imported);
    }

    @Transactional(readOnly = true)
    public List<KnowledgeDocumentResponse> list(UUID userId) {
        return documentRepository.findAllByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(document -> toResponse(userId, document))
                .toList();
    }

    @Transactional(readOnly = true)
    public KnowledgeDocumentResponse get(UUID userId, UUID documentId) {
        return toResponse(userId, requireDocument(userId, documentId));
    }

    /**
     * Hybrid retrieval: dense (embedding cosine) + sparse (term frequency), fused with
     * reciprocal rank fusion.
     *
     * <p>The two retrievers produce scores on incomparable scales — cosine similarity is
     * bounded in [0,1] while the lexical score is an unbounded occurrence count — so they
     * are combined by rank rather than by value. RRF needs no normalisation and degrades
     * gracefully when one retriever returns nothing, which is exactly what happens before
     * an embedding model is configured.
     */
    public List<KnowledgeSearchResult> search(UUID userId, String rawQuery, int requestedLimit) {
        String query = normalizeQuery(rawQuery);
        int limit = Math.max(1, Math.min(10, requestedLimit));

        // Pick up anything ingested before an embedding model existed, and drop vectors
        // from a model the user has since replaced.
        indexer.reconcileModel(userId);
        indexer.backfill(userId, CANDIDATE_DEPTH);

        List<KnowledgeSearchResult> lexical = lexicalSearch(userId, query, CANDIDATE_DEPTH);
        List<KnowledgeSearchResult> dense = denseSearch(userId, query, CANDIDATE_DEPTH);
        return fuse(lexical, dense, limit);
    }

    private List<KnowledgeSearchResult> lexicalSearch(UUID userId, String query, int depth) {
        Map<UUID, KnowledgeDocument> documents = documentRepository.findAllByUserIdOrderByCreatedAtDesc(userId)
                .stream().collect(Collectors.toMap(KnowledgeDocument::getId, Function.identity()));
        Set<String> terms = searchTerms(query);
        return chunkRepository.findAllByUserId(userId).stream()
                .map(chunk -> scored(documents.get(chunk.getDocumentId()), chunk, terms))
                .filter(result -> result != null && result.score() > 0)
                .sorted(Comparator.comparingDouble(KnowledgeSearchResult::score).reversed())
                .limit(depth)
                .toList();
    }

    /** Embedding-based retrieval; empty when the user has no embedding model configured. */
    private List<KnowledgeSearchResult> denseSearch(UUID userId, String query, int depth) {
        KnowledgeEmbeddingService.EmbeddingVector queryVector;
        try {
            queryVector = embeddingService.embedQuery(userId, query);
        } catch (RuntimeException exception) {
            // Semantic recall is an enhancement; never let it take down keyword search.
            return List.of();
        }
        if (queryVector == null) {
            return List.of();
        }
        Map<UUID, KnowledgeDocument> documents = documentRepository.findAllByUserIdOrderByCreatedAtDesc(userId)
                .stream().collect(Collectors.toMap(KnowledgeDocument::getId, Function.identity()));
        return vectorStore.search(userId, queryVector.toPgVectorLiteral(), queryVector.dimension(), depth).stream()
                // Nearest-neighbour search always returns *something*: without a floor, a
                // question about databases would "match" the only JVM note in the library
                // and the agent would be handed irrelevant context as if it were evidence.
                .filter(hit -> hit.similarity() >= minSimilarity)
                .map(hit -> {
                    KnowledgeDocument document = documents.get(hit.documentId());
                    if (document == null) return null;
                    return new KnowledgeSearchResult(
                            document.getId(), document.getTitle(), document.getSourceType(),
                            document.getSourceUrl(), hit.content(),
                            Math.round(hit.similarity() * 10000.0) / 10000.0);
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /**
     * Reciprocal rank fusion. A chunk appearing in both lists outranks one that is only
     * strong in a single retriever, which is what makes hybrid better than either alone.
     */
    private List<KnowledgeSearchResult> fuse(
            List<KnowledgeSearchResult> lexical, List<KnowledgeSearchResult> dense, int limit) {
        Map<String, Double> fusedScore = new java.util.LinkedHashMap<>();
        Map<String, KnowledgeSearchResult> byKey = new java.util.LinkedHashMap<>();
        for (List<KnowledgeSearchResult> ranking : List.of(dense, lexical)) {
            for (int rank = 0; rank < ranking.size(); rank++) {
                KnowledgeSearchResult result = ranking.get(rank);
                String key = result.documentId() + "#" + result.content().hashCode();
                fusedScore.merge(key, 1.0 / (RRF_K + rank + 1), Double::sum);
                byKey.putIfAbsent(key, result);
            }
        }
        return fusedScore.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(limit)
                .map(entry -> {
                    KnowledgeSearchResult base = byKey.get(entry.getKey());
                    return new KnowledgeSearchResult(
                            base.documentId(), base.title(), base.sourceType(), base.sourceUrl(),
                            base.content(), Math.round(entry.getValue() * 100000.0) / 100000.0);
                })
                .toList();
    }

    /** Embedding coverage for the current user, surfaced in the knowledge page. */
    @Transactional(readOnly = true)
    public KnowledgeIndexStatus indexStatus(UUID userId) {
        return new KnowledgeIndexStatus(
                embeddingService.isConfigured(userId),
                embeddingService.modelName(userId),
                vectorStore.countEmbedded(userId),
                vectorStore.countPending(userId));
    }

    public record KnowledgeIndexStatus(
            boolean embeddingConfigured, String embeddingModel, long embeddedChunks, long pendingChunks) {
    }

    @Transactional
    public void delete(UUID userId, UUID documentId) {
        KnowledgeDocument document = requireDocument(userId, documentId);
        documentRepository.delete(document);
        documentRepository.flush();
    }

    private KnowledgeDocument requireDocument(UUID userId, UUID documentId) {
        return documentRepository.findByIdAndUserId(documentId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("知识文档不存在"));
    }

    private KnowledgeDocumentResponse toResponse(UUID userId, KnowledgeDocument document) {
        return KnowledgeDocumentResponse.from(
                document, chunkRepository.countByDocumentIdAndUserId(document.getId(), userId));
    }

    private int storeChunks(KnowledgeDocument document) {
        List<String> pieces = chunks(document.getRawContent());
        List<KnowledgeChunk> entities = new ArrayList<>(pieces.size());
        for (int index = 0; index < pieces.size(); index++) {
            entities.add(KnowledgeChunk.create(document.getUserId(), document.getId(), index + 1, pieces.get(index)));
        }
        chunkRepository.saveAll(entities);
        scheduleEmbedding(document.getUserId(), entities);
        return entities.size();
    }

    /**
     * Embeds the new chunks once the ingest transaction has committed.
     *
     * <p>The indexer runs in its own transaction and updates rows by id, so it must not
     * start before those rows are visible. Running after commit also keeps the embedding
     * HTTP call outside the ingest transaction, so a slow provider never holds a database
     * connection open.
     */
    private void scheduleEmbedding(UUID userId, List<KnowledgeChunk> entities) {
        if (entities.isEmpty()) {
            return;
        }
        List<UUID> ids = entities.stream().map(KnowledgeChunk::getId).toList();
        List<String> contents = entities.stream().map(KnowledgeChunk::getContent).toList();
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            indexer.indexNewChunks(userId, ids, contents);
            return;
        }
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        indexer.indexNewChunks(userId, ids, contents);
                    }
                });
    }

    private List<String> chunks(String content) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < content.length()) {
            int end = Math.min(content.length(), start + CHUNK_SIZE);
            if (end < content.length()) {
                int paragraph = content.lastIndexOf('\n', end);
                if (paragraph >= start + CHUNK_SIZE / 2) end = paragraph;
            }
            String chunk = content.substring(start, end).strip();
            if (!chunk.isBlank()) chunks.add(chunk);
            if (end >= content.length()) break;
            start = Math.max(start + 1, end - CHUNK_OVERLAP);
        }
        return chunks;
    }

    private KnowledgeSearchResult scored(
            KnowledgeDocument document, KnowledgeChunk chunk, Set<String> terms) {
        if (document == null) return null;
        String content = chunk.getContent().toLowerCase(Locale.ROOT);
        String title = document.getTitle().toLowerCase(Locale.ROOT);
        double score = 0;
        for (String term : terms) {
            score += occurrences(content, term) * Math.min(4, Math.max(1, term.length() / 2.0));
            if (title.contains(term)) score += 5;
        }
        if (score == 0) return null;
        return new KnowledgeSearchResult(
                document.getId(), document.getTitle(), document.getSourceType(), document.getSourceUrl(),
                chunk.getContent(), Math.round(score * 100.0) / 100.0);
    }

    private int occurrences(String text, String term) {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(term, from)) >= 0) {
            count++;
            from += Math.max(1, term.length());
        }
        return count;
    }

    private Set<String> searchTerms(String query) {
        String normalized = query.toLowerCase(Locale.ROOT);
        Set<String> terms = new LinkedHashSet<>();
        for (String token : normalized.split("[^\\p{L}\\p{N}+#.]+")) {
            if (token.length() >= 2) terms.add(token);
        }
        String han = normalized.replaceAll("[^\\p{IsHan}]", "");
        for (int index = 0; index + 2 <= han.length(); index++) {
            terms.add(han.substring(index, index + 2));
        }
        if (terms.isEmpty()) terms.add(normalized);
        return terms;
    }

    private String normalizeQuery(String value) {
        String query = value == null ? "" : value.strip();
        if (query.isBlank()) throw new IllegalArgumentException("检索内容不能为空");
        if (query.length() > 400) throw new IllegalArgumentException("检索内容不能超过 400 个字符");
        return query;
    }

    private String safeFilename(String rawFilename) {
        if (rawFilename == null || rawFilename.isBlank()) throw new IllegalArgumentException("文件名不能为空");
        String normalized = rawFilename.replace('\\', '/');
        String filename = normalized.substring(normalized.lastIndexOf('/') + 1).strip();
        if (filename.isBlank() || filename.length() > 255 || filename.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("文件名不合法或过长");
        }
        return filename;
    }

    private String extension(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        for (String extension : List.of(".pdf", ".docx", ".txt", ".md")) {
            if (lower.endsWith(extension)) return extension;
        }
        throw new IllegalArgumentException("只支持 PDF、DOCX、TXT 或 Markdown 文件");
    }

    private void validateSignature(byte[] content, String extension) {
        boolean valid = switch (extension) {
            case ".pdf" -> content.length >= 4 && content[0] == '%' && content[1] == 'P'
                    && content[2] == 'D' && content[3] == 'F';
            case ".docx" -> content.length >= 4 && content[0] == 'P' && content[1] == 'K';
            default -> true;
        };
        if (!valid) throw new IllegalArgumentException("文件内容与扩展名不匹配");
    }

    private String titleFromFilename(String filename) {
        int dot = filename.lastIndexOf('.');
        return normalizeTitle(dot > 0 ? filename.substring(0, dot) : filename, filename);
    }

    private String normalizeTitle(String value, String fallback) {
        String title = value == null ? "" : value.strip();
        if (title.isBlank()) title = fallback;
        return title.length() <= 240 ? title : title.substring(0, 240);
    }

    private String contentType(String extension) {
        return switch (extension) {
            case ".pdf" -> "application/pdf";
            case ".docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case ".md" -> "text/markdown";
            default -> "text/plain";
        };
    }
}
