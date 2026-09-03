package com.projectardor.recap.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.projectardor.calendar.service.CalendarTaskService;
import com.projectardor.common.json.LlmJsonParser;
import com.projectardor.common.web.ResourceNotFoundException;
import com.projectardor.llm.service.LlmGateway;
import com.projectardor.profile.service.ProfileService;
import com.projectardor.recap.domain.*;
import com.projectardor.recap.repository.*;
import com.projectardor.recap.web.InterviewRecapResponse;

import tools.jackson.databind.JsonNode;

@Service
public class InterviewRecapService {
    private static final String PROMPT = """
            你是严谨的中文技术面试复盘编辑。根据用户提供的原始转录、回忆或笔记，按真实发生顺序整理。
            只输出 JSON 对象，不要 Markdown：
            {"title":"简短面经标题","company":"材料可确认的公司或空字符串","targetRole":"材料可确认的岗位或空字符串","occurredAt":"材料可确认的带时区 ISO-8601 时间或空字符串","overview":"高密度整体概况","strengths":["有原文依据的优势"],"weaknesses":["有原文依据的薄弱点"],"questions":[{"questionText":"面试官问题本意","candidateAnswer":"候选人实际回答摘要；材料没有则为空","followUps":["按顺序记录追问和补充"],"assessment":"保守复盘，区分有效部分、遗漏和无法验证处","performance":"STRONG|MIXED|WEAK|UNKNOWN","weaknessReason":"只有表现存在问题且有依据时填写，否则为空","betterAnswer":"改进建议，明确不是当时回答；材料不足可为空","tags":["知识点"]}]}。
            不得虚构问题、回答、代码、指标或面试官评价；不改变数字与单位；无法确认时写材料未说明或使用 UNKNOWN。
            不能把表达完整等同于技术正确。主问题及连续追问必须在同一组。questions 至少 1 项，最多 30 项。
            """;

    private final InterviewRecapRepository recapRepository;
    private final InterviewRecapQuestionRepository questionRepository;
    private final MemoryCardRepository cardRepository;
    private final MemoryCardReviewRepository reviewRepository;
    private final LlmGateway llmGateway;
    private final LlmJsonParser jsonParser;
    private final CalendarTaskService calendarTaskService;
    private final TransactionTemplate transactions;
    private final ProfileService profileService;

    public InterviewRecapService(InterviewRecapRepository recapRepository,
            InterviewRecapQuestionRepository questionRepository, MemoryCardRepository cardRepository,
            MemoryCardReviewRepository reviewRepository, LlmGateway llmGateway,
            LlmJsonParser jsonParser, CalendarTaskService calendarTaskService,
            org.springframework.transaction.PlatformTransactionManager transactionManager,
            ProfileService profileService) {
        this.recapRepository = recapRepository; this.questionRepository = questionRepository;
        this.cardRepository = cardRepository; this.reviewRepository = reviewRepository;
        this.llmGateway = llmGateway; this.jsonParser = jsonParser; this.calendarTaskService = calendarTaskService;
        this.transactions = new TransactionTemplate(transactionManager);
        this.profileService = profileService;
    }

    public InterviewRecapResponse organize(UUID userId, String rawContent, InterviewRecapSource sourceType) {
        String content = required(rawContent, "面试内容不能为空", 50000);
        if (content.length() < 30) throw new IllegalArgumentException("面试内容太短，请至少提供一段问题或回忆");
        String inputHash = sha256((sourceType == null ? InterviewRecapSource.NOTES : sourceType).name() + "\n" + content);
        var existing = recapRepository.findByUserIdAndInputHash(userId, inputHash);
        if (existing.isPresent()) return detail(userId, existing.get().getId());

        LlmGateway.LlmResult result = llmGateway.completeJson(userId, PROMPT,
                "输入类型：" + (sourceType == null ? InterviewRecapSource.NOTES : sourceType) + "\n原始材料：\n" + content);
        JsonNode root = jsonParser.parseObject(result.content());
        List<QuestionDraft> drafts = parseQuestions(root.path("questions"));
        if (drafts.isEmpty()) throw new IllegalStateException("没有从材料中识别出可复盘的问题");

        return transactions.execute(status -> {
            InterviewRecap recap = recapRepository.save(InterviewRecap.create(userId,
                    text(root, "title", "面试复盘", 240), nullable(root, "company", 160),
                    nullable(root, "targetRole", 160), parseInstant(nullable(root, "occurredAt", 80)),
                    sourceType == null ? InterviewRecapSource.NOTES : sourceType, content, inputHash,
                    text(root, "overview", "已按原始材料整理逐题复盘。", 12000),
                    stringList(root.path("strengths"), 20, 1000), stringList(root.path("weaknesses"), 20, 1000), result.model()));
            List<InterviewRecapQuestion> questions = new ArrayList<>();
            for (int i = 0; i < drafts.size(); i++) {
                QuestionDraft draft = drafts.get(i);
                questions.add(InterviewRecapQuestion.create(userId, recap.getId(), i + 1,
                        draft.questionText(), draft.candidateAnswer(), draft.followUps(), draft.assessment(),
                        draft.performance(), draft.weaknessReason(), draft.betterAnswer(), draft.tags()));
            }
            questionRepository.saveAll(questions);
            return InterviewRecapResponse.from(recap, questions);
        });
    }

    public InterviewRecapResponse organize(UUID userId, String rawContent) {
        return organize(userId, rawContent, InterviewRecapSource.NOTES);
    }

    @Transactional(readOnly = true)
    public List<InterviewRecapResponse> list(UUID userId) {
        Map<UUID, List<InterviewRecapQuestion>> questionsByRecap = questionRepository
                .findAllByUserIdOrderByRecapIdAscSequenceNumberAsc(userId).stream()
                .collect(Collectors.groupingBy(InterviewRecapQuestion::getRecapId));
        return recapRepository.findAllByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(recap -> InterviewRecapResponse.from(
                        recap, questionsByRecap.getOrDefault(recap.getId(), List.of())))
                .toList();
    }

    @Transactional(readOnly = true)
    public InterviewRecapResponse detail(UUID userId, UUID recapId) {
        InterviewRecap recap = recapRepository.findByIdAndUserId(recapId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("面经不存在"));
        return InterviewRecapResponse.from(recap,
                questionRepository.findAllByUserIdAndRecapIdOrderBySequenceNumber(userId, recapId));
    }

    @Transactional
    public void delete(UUID userId, UUID recapId) {
        InterviewRecap recap = recapRepository.findByIdAndUserId(recapId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("面经不存在"));
        recapRepository.delete(recap);
    }

    @Transactional
    public MemoryCard createCard(UUID userId, MemoryCardSource sourceType, String sourceLabel, String sourceUrl,
            String front, String back, List<String> tags, Instant nextReviewAt) {
        MemoryCardSource resolvedSource = sourceType == null ? MemoryCardSource.KNOWLEDGE : sourceType;
        String url = nullable(sourceUrl, 1000);
        if (resolvedSource == MemoryCardSource.WEB && url == null) {
            throw new IllegalArgumentException("网络题必须提供可核验的来源链接");
        }
        MemoryCard card = cardRepository.save(MemoryCard.create(userId, null, resolvedSource,
                nullable(sourceLabel, 240), url, required(front, "卡片问题不能为空", 12000),
                required(back, "卡片答案不能为空", 20000), normalizeTags(tags),
                nextReviewAt == null ? Instant.now() : nextReviewAt));
        refreshDate(userId, localDate(userId, card.getNextReviewAt()));
        return card;
    }

    @Transactional(readOnly = true)
    public List<MemoryCard> listCards(UUID userId, boolean dueOnly) {
        if (dueOnly) return cardRepository.findAllByUserIdAndStatusNotAndNextReviewAtLessThanEqualOrderByNextReviewAtAsc(
                userId, MemoryCardStatus.SUSPENDED, Instant.now());
        return cardRepository.findAllByUserIdOrderByNextReviewAtAscCreatedAtDesc(userId);
    }

    @Transactional
    public MemoryCard review(UUID userId, UUID cardId, MemoryCardRating rating) {
        MemoryCard card = getCard(userId, cardId);
        if (card.getStatus() == MemoryCardStatus.SUSPENDED) throw new IllegalStateException("已暂停的卡片不能复习");
        if (rating == null) throw new IllegalArgumentException("复习结果不能为空");
        LocalDate previousDate = localDate(userId, card.getNextReviewAt());
        MemoryCard.ReviewResult result = card.review(rating, Instant.now());
        reviewRepository.save(MemoryCardReview.create(userId, cardId, rating,
                result.previousIntervalDays(), result.nextIntervalDays()));
        LocalDate nextDate = localDate(userId, card.getNextReviewAt());
        refreshDate(userId, previousDate);
        if (!nextDate.equals(previousDate)) refreshDate(userId, nextDate);
        return card;
    }

    @Transactional
    public MemoryCard suspend(UUID userId, UUID cardId, boolean suspended) {
        MemoryCard card = getCard(userId, cardId);
        LocalDate previousDate = localDate(userId, card.getNextReviewAt());
        card.suspend(suspended);
        LocalDate nextDate = localDate(userId, card.getNextReviewAt());
        refreshDate(userId, previousDate);
        if (!nextDate.equals(previousDate)) refreshDate(userId, nextDate);
        return card;
    }

    @Transactional
    public void deleteCard(UUID userId, UUID cardId) {
        MemoryCard card = getCard(userId, cardId);
        LocalDate date = localDate(userId, card.getNextReviewAt());
        cardRepository.delete(card);
        cardRepository.flush();
        refreshDate(userId, date);
    }

    @Transactional
    public void deleteAllCards(UUID userId) {
        List<MemoryCard> cards = cardRepository.findAllByUserIdOrderByNextReviewAtAscCreatedAtDesc(userId);
        Set<LocalDate> dates = cards.stream().map(card -> localDate(userId, card.getNextReviewAt()))
                .collect(java.util.stream.Collectors.toSet());
        cardRepository.deleteAll(cards);
        cardRepository.flush();
        dates.forEach(date -> refreshDate(userId, date));
    }

    private MemoryCard getCard(UUID userId, UUID cardId) {
        return cardRepository.findByIdAndUserId(cardId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("记忆卡不存在"));
    }

    private void refreshDate(UUID userId, LocalDate date) {
        ZoneId userZone = userZone(userId);
        Instant from = date.atStartOfDay(userZone).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(userZone).toInstant();
        long count = cardRepository.countByUserIdAndStatusNotAndNextReviewAtGreaterThanEqualAndNextReviewAtLessThan(
                userId, MemoryCardStatus.SUSPENDED, from, to);
        Instant firstDue = cardRepository.findFirstByUserIdAndStatusNotAndNextReviewAtGreaterThanEqualAndNextReviewAtLessThanOrderByNextReviewAtAsc(
                userId, MemoryCardStatus.SUSPENDED, from, to).map(MemoryCard::getNextReviewAt).orElse(from);
        calendarTaskService.refreshMemoryCardReview(userId, date, Math.toIntExact(count), firstDue);
    }

    private LocalDate localDate(UUID userId, Instant value) { return value.atZone(userZone(userId)).toLocalDate(); }

    private ZoneId userZone(UUID userId) { return ZoneId.of(profileService.get(userId).getTimezone()); }

    private List<QuestionDraft> parseQuestions(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<QuestionDraft> result = new ArrayList<>();
        for (JsonNode item : node) {
            if (result.size() >= 30) break;
            String question = nullable(item, "questionText", 12000);
            if (question == null) continue;
            result.add(new QuestionDraft(question, nullable(item, "candidateAnswer", 20000),
                    stringList(item.path("followUps"), 20, 4000),
                    text(item, "assessment", "材料不足，暂无法判断回答质量。", 12000),
                    enumValue(QuestionPerformance.class, nullable(item, "performance", 32), QuestionPerformance.UNKNOWN),
                    nullable(item, "weaknessReason", 12000), nullable(item, "betterAnswer", 20000),
                    stringList(item.path("tags"), 12, 80)));
        }
        return result;
    }

    private List<String> stringList(JsonNode node, int maxItems, int maxLength) {
        if (!node.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item.asText("").strip();
            if (!value.isBlank()) result.add(value.substring(0, Math.min(value.length(), maxLength)));
            if (result.size() >= maxItems) break;
        }
        return result;
    }
    private List<String> normalizeTags(List<String> tags) {
        if (tags == null) return List.of();
        return tags.stream().filter(Objects::nonNull).map(String::strip).filter(value -> !value.isBlank())
                .map(value -> value.substring(0, Math.min(value.length(), 80))).distinct().limit(12).toList();
    }
    private String text(JsonNode node, String field, String fallback, int max) {
        String value = nullable(node, field, max); return value == null ? fallback : value;
    }
    private String nullable(JsonNode node, String field, int max) { return nullable(node.path(field).asText(""), max); }
    private String nullable(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip(); return normalized.substring(0, Math.min(normalized.length(), max));
    }
    private String required(String value, String message, int max) {
        String normalized = nullable(value, max); if (normalized == null) throw new IllegalArgumentException(message); return normalized;
    }
    private Instant parseInstant(String value) {
        if (value == null) return null;
        try { return OffsetDateTime.parse(value).toInstant(); } catch (RuntimeException ignored) { return null; }
    }
    private <E extends Enum<E>> E enumValue(Class<E> type, String value, E fallback) {
        if (value == null) return fallback;
        try { return Enum.valueOf(type, value.toUpperCase(Locale.ROOT)); } catch (RuntimeException ignored) { return fallback; }
    }
    private String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException("无法生成内容指纹", exception); }
    }
    private record QuestionDraft(String questionText, String candidateAnswer, List<String> followUps,
            String assessment, QuestionPerformance performance, String weaknessReason, String betterAnswer, List<String> tags) {}
}
