package com.projectardor.assistant.memory.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.projectardor.assistant.ReasoningDepth;
import com.projectardor.assistant.conversation.domain.ConversationMessage;
import com.projectardor.assistant.memory.web.AgentMemoryItemResponse;
import com.projectardor.common.json.LlmJsonParser;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import tools.jackson.databind.JsonNode;
import com.projectardor.assistant.conversation.service.AgentConversationStore;
import com.projectardor.assistant.execution.service.LangChainModelFactory;
import com.projectardor.assistant.execution.service.ToolIntentPolicy;

/**
 * Extracts new long-term facts from recent user messages after an exchange. Writes are
 * INFERRED, need a verbatim evidence fragment from a user message, and correct existing
 * facts through invalidation instead of leaving contradictions behind.
 */
@Service
public class MemoryConsolidationService {
    private static final Logger log = LoggerFactory.getLogger(MemoryConsolidationService.class);
    private static final Duration MIN_INTERVAL = Duration.ofMinutes(5);
    private static final int MAX_CANDIDATES = 3;
    private static final Set<String> KINDS = Set.of("profile", "preference", "goal", "constraint", "state", "fact");
    private static final Pattern SIGNAL = Pattern.compile(
            "(我是|我的|我在|我目前|我今年|我打算|我准备|我想|目标|偏好|喜欢|讨厌|希望|想要|打算|计划|记住|"
            + "以后|正在|实习|秋招|校招|研[一二三]|大[一二三四]|年级|专业|学校|本科|硕士|博士|offer|习惯|倾向)");
    private static final String SYSTEM = """
            你负责整理用户的长期记忆。只输出 JSON 对象，不要用代码围栏包住 JSON。
            格式：{"memories":[{"op":"ADD 或 UPDATE","id":"UPDATE 时的记忆 UUID","content":"简洁事实",
            "kind":"profile 或 preference 或 goal 或 constraint 或 state 或 fact",
            "confidence":0.0,"evidence":"用户原话片段"}]}。
            evidence 必须逐字来自给定的用户发言；没有值得长期保存的新事实时输出 {"memories":[]}。
            只能记录用户本人明确表达或可直接归纳的稳定信息，不要记录助手推测、检索资料或一次性话题。
            UPDATE 只用于纠正已有记忆里被用户更新的事实，content 写修订后的完整内容。
            """;

    private final AgentConversationStore store;
    private final AgentMemoryService memoryService;
    private final LangChainModelFactory models;
    private final LlmJsonParser parser;
    private final ConcurrentHashMap<UUID, Instant> lastRuns = new ConcurrentHashMap<>();

    public MemoryConsolidationService(AgentConversationStore store, AgentMemoryService memoryService,
            LangChainModelFactory models, LlmJsonParser parser) {
        this.store = store;
        this.memoryService = memoryService;
        this.models = models;
        this.parser = parser;
    }

    @Async("memoryConsolidationExecutor")
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void consolidate(MemoryConsolidationRequested event) {
        try {
            Instant now = Instant.now();
            Instant previous = lastRuns.get(event.userId());
            if (previous != null && previous.plus(MIN_INTERVAL).isAfter(now)) return;
            List<String> userTexts = recentUserTexts(store.recent(event.userId(), event.conversationId()));
            if (userTexts.isEmpty() || userTexts.stream().noneMatch(text -> SIGNAL.matcher(text).find())) return;
            lastRuns.put(event.userId(), now);
            var existing = memoryService.listItems(event.userId()).stream()
                    .filter(AgentMemoryItemResponse::valid).limit(50).toList();
            String output = models.create(event.userId(), ReasoningDepth.QUICK)
                    .chat(SystemMessage.from(SYSTEM), UserMessage.from(prompt(userTexts, existing)))
                    .aiMessage().text();
            apply(event.userId(), parser.parseObject(output), userTexts, existing);
        } catch (RuntimeException exception) {
            // Never log conversation text or provider details.
            log.debug("Automatic memory consolidation unavailable: userId={}, type={}",
                    event.userId(), exception.getClass().getSimpleName());
        }
    }

    static List<String> recentUserTexts(List<ConversationMessage> messages) {
        List<String> texts = new ArrayList<>();
        for (ConversationMessage message : messages) {
            if (!"USER".equals(message.getRole())) continue;
            String content = message.getContent();
            if (content == null || content.isBlank()) continue;
            texts.add(content.length() > 1500 ? content.substring(0, 1500) : content);
        }
        return texts.subList(Math.max(0, texts.size() - 4), texts.size());
    }

    static List<Candidate> candidates(JsonNode node, List<String> userTexts, Set<UUID> existingIds) {
        List<Candidate> result = new ArrayList<>();
        if (node == null) return result;
        JsonNode memories = node.path("memories");
        if (!memories.isArray()) return result;
        String corpus = String.join("\n", userTexts);
        for (JsonNode item : memories) {
            if (result.size() >= MAX_CANDIDATES) break;
            String op = item.path("op").asText("").strip().toUpperCase(Locale.ROOT);
            if (!op.equals("ADD") && !op.equals("UPDATE")) continue;
            String content = item.path("content").asText("").strip();
            String evidence = item.path("evidence").asText("").strip();
            if (content.isBlank() || content.length() > 500) continue;
            if (evidence.isBlank() || !ToolIntentPolicy.verbatimQuote(corpus, evidence)) continue;
            String kind = item.path("kind").asText("fact").strip().toLowerCase(Locale.ROOT);
            if (!KINDS.contains(kind)) kind = "fact";
            double confidence = Math.max(0.4, Math.min(0.9, item.path("confidence").asDouble(0.7)));
            UUID id = null;
            if (op.equals("UPDATE")) {
                try {
                    id = UUID.fromString(item.path("id").asText(""));
                } catch (IllegalArgumentException exception) {
                    continue;
                }
                if (!existingIds.contains(id)) continue;
            }
            result.add(new Candidate(op, id, content, kind, confidence));
        }
        return result;
    }

    private void apply(UUID userId, JsonNode node, List<String> userTexts, List<AgentMemoryItemResponse> existing) {
        Set<UUID> existingIds = new LinkedHashSet<>();
        existing.forEach(item -> existingIds.add(item.id()));
        for (Candidate candidate : candidates(node, userTexts, existingIds)) {
            if ("ADD".equals(candidate.op())) {
                memoryService.add(userId, candidate.content(), candidate.kind(), "INFERRED", null, (float) candidate.confidence());
            } else if ("UPDATE".equals(candidate.op()) && candidate.id() != null) {
                memoryService.replace(userId, candidate.id(), candidate.content(), candidate.kind(), "INFERRED", (float) candidate.confidence());
            }
        }
    }

    private static String prompt(List<String> userTexts, List<AgentMemoryItemResponse> existing) {
        StringBuilder input = new StringBuilder("用户最近发言：\n");
        for (String text : userTexts) input.append("- ").append(text).append('\n');
        input.append("\n已有记忆：\n");
        if (existing.isEmpty()) input.append("（空）\n");
        else existing.forEach(item -> input.append("- [").append(item.id()).append("] ")
                .append(item.content()).append('\n'));
        return input.toString();
    }

    /** One accepted write, after evidence and ownership checks. */
    record Candidate(String op, UUID id, String content, String kind, double confidence) {}
}
