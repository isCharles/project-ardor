package com.projectardor.learning.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.projectardor.calendar.domain.CalendarTaskSource;
import com.projectardor.calendar.service.CalendarTaskService;
import com.projectardor.common.json.LlmJsonParser;
import com.projectardor.common.web.ResourceNotFoundException;
import com.projectardor.learning.domain.LearningPlan;
import com.projectardor.learning.domain.LearningSourceType;
import com.projectardor.learning.domain.LearningStatus;
import com.projectardor.learning.repository.LearningPlanRepository;
import com.projectardor.llm.service.LlmGateway;
import com.projectardor.profile.service.ProfileService;
import com.projectardor.recap.service.InterviewRecordSourceAccess;
import com.projectardor.resume.repository.ResumeRepository;
import com.projectardor.knowledge.repository.KnowledgeDocumentRepository;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class LearningPlanService {
    private final LearningPlanRepository repository;
    private final LlmGateway llmGateway;
    private final LlmJsonParser jsonParser;
    private final ObjectMapper objectMapper;
    private final CalendarTaskService calendarTaskService;
    private final ProfileService profileService;
    private final InterviewRecordSourceAccess interviewSources;
    private final ResumeRepository resumeRepository;
    private final KnowledgeDocumentRepository knowledgeRepository;
    private final LearningPlanCreationStore creationStore;
    private final LearningPlanGenerationGate generationGate;
    private final LearningPlanAgentSlots agentSlots;
    private final TransactionTemplate transactions;

    public LearningPlanService(LearningPlanRepository repository, LlmGateway llmGateway,
            LlmJsonParser jsonParser, ObjectMapper objectMapper,
            CalendarTaskService calendarTaskService, ProfileService profileService,
            InterviewRecordSourceAccess interviewSources,
            ResumeRepository resumeRepository, KnowledgeDocumentRepository knowledgeRepository,
            LearningPlanCreationStore creationStore, LearningPlanGenerationGate generationGate,
            LearningPlanAgentSlots agentSlots, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.llmGateway = llmGateway;
        this.jsonParser = jsonParser;
        this.objectMapper = objectMapper;
        this.calendarTaskService = calendarTaskService;
        this.profileService = profileService;
        this.interviewSources = interviewSources;
        this.resumeRepository = resumeRepository;
        this.knowledgeRepository = knowledgeRepository;
        this.creationStore = creationStore;
        this.generationGate = generationGate;
        this.agentSlots = agentSlots;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public LearningPlan create(UUID userId, String rawConcept, String rawReason,
            LearningSourceType sourceType, UUID sourceId, Instant scheduledAt) {
        return create(userId, null, rawConcept, rawReason, sourceType, sourceId, scheduledAt);
    }

    public LearningPlan create(UUID userId, UUID requestId, String rawConcept, String rawReason,
            LearningSourceType sourceType, UUID sourceId, Instant scheduledAt) {
        return createInternal(userId, requestId, rawConcept, rawReason, sourceType, sourceId, scheduledAt, false);
    }

    public LearningPlan createForAgent(UUID userId, UUID logicalActionId, UUID runId,
            String rawConcept, String rawReason, LearningSourceType sourceType, UUID sourceId, Instant scheduledAt) {
        String concept = required(rawConcept, "学习概念不能为空", 160);
        String reason = optional(rawReason, 4000);
        LearningSourceType source = sourceType == null ? LearningSourceType.AGENT : sourceType;
        UUID requestId = logicalActionId == null || runId == null ? null
                : agentSlots.resolve(userId, logicalActionId, runId, concept, reason, source, sourceId, scheduledAt);
        return createInternal(userId, requestId, concept, reason, source, sourceId, scheduledAt, true);
    }

    private LearningPlan createInternal(UUID userId, UUID requestId, String rawConcept, String rawReason,
            LearningSourceType sourceType, UUID sourceId, Instant scheduledAt, boolean agentRetry) {
        String concept = required(rawConcept, "学习概念不能为空", 160);
        String reason = optional(rawReason, 4000);
        LearningSourceType source = sourceType == null ? LearningSourceType.MANUAL : sourceType;
        // Agent retries can regenerate wording and schedule. Its trusted per-action slot is the identity;
        // manual requests remain strict about every input field.
        String requestHash = requestId == null ? null : agentRetry
                ? requestHash(requestId.toString(), null, LearningSourceType.AGENT, null, null)
                : requestHash(concept, reason, source, sourceId, scheduledAt);
        LearningPlan previous = creationStore.existing(userId, requestId, requestHash);
        if (previous != null) return previous;
        try (LearningPlanGenerationGate.Reservation ignored = generationGate.reserve(userId, requestId, requestHash)) {
            // Another request may have committed between the first lookup and reservation.
            previous = creationStore.existing(userId, requestId, requestHash);
            if (previous != null) return previous;
            if (source == LearningSourceType.RECAP && sourceId != null) interviewSources.requireLegacy(userId, sourceId);
            if (source == LearningSourceType.RESUME && sourceId != null
                    && resumeRepository.findByIdAndUserId(sourceId, userId).isEmpty()) {
                throw new ResourceNotFoundException("简历来源不存在");
            }
            if (source == LearningSourceType.KNOWLEDGE && sourceId != null
                    && knowledgeRepository.findByIdAndUserId(sourceId, userId).isEmpty()) {
                throw new ResourceNotFoundException("知识库来源不存在");
            }
            if (scheduledAt != null && !scheduledAt.isAfter(Instant.now())) {
                throw new IllegalArgumentException("学习时间已过，请重新安排");
            }
            Instant schedule = scheduledAt == null ? tomorrowMorning(userId) : scheduledAt;
            LlmGateway.LlmResult result = llmGateway.completeJson(userId, generationPrompt(),
                    "学习主题：" + concept + "\n安排原因：" + (reason == null ? "用户主动学习" : reason));
            JsonNode root = jsonParser.parseObject(result.content());
            Map<String, Object> lesson = lesson(root.path("lesson"));
            List<Map<String, Object>> exercises = exercises(root.path("exercises"));
            return creationStore.save(userId, requestId, LearningPlan.create(
                    userId, requestId, requestHash, concept, reason, source, sourceId,
                    lesson, exercises, schedule, result.model()));
        }
    }

    @Transactional(readOnly = true)
    public List<LearningPlan> list(UUID userId) {
        return repository.findAllByUserIdOrderByScheduledAtAscCreatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public List<LearningPlanCreationStore.RequestStatus> requestStatuses(UUID userId, List<UUID> requestIds) {
        if (requestIds == null || requestIds.isEmpty() || requestIds.size() > 50) {
            throw new IllegalArgumentException("一次可查询 1 到 50 个学习请求");
        }
        return requestIds.stream().distinct()
                .map(requestId -> creationStore.requestStatus(userId, requestId)).toList();
    }

    @Transactional(readOnly = true)
    public LearningPlan get(UUID userId, UUID planId) {
        return repository.findByIdAndUserId(planId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("学习计划不存在"));
    }

    @Transactional
    public LearningPlan begin(UUID userId, UUID planId) {
        LearningPlan plan = repository.findOwnedForUpdate(planId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("学习计划不存在"));
        plan.markInProgress();
        return plan;
    }

    @Transactional
    public LearningPlan updateArrangement(UUID userId, UUID planId, String reason, Instant scheduledAt) {
        LearningPlan plan = repository.findOwnedForUpdate(planId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("学习计划不存在"));
        if (reason == null && scheduledAt == null) throw new IllegalArgumentException("请指定说明或安排时间");
        String normalized = reason == null ? null : optional(reason, 4000);
        if (scheduledAt != null && (!scheduledAt.isAfter(Instant.now()) || plan.getStatus() == LearningStatus.COMPLETED)) {
            throw new IllegalArgumentException("只能为未完成的学习计划安排未来时间；已完成主题可创建新的复习计划");
        }
        plan.updateArrangement(normalized, reason != null, scheduledAt);
        syncCalendar(plan);
        return plan;
    }

    public LearningPlan submit(UUID userId, UUID planId, List<String> rawAnswers) {
        return submit(userId, planId, rawAnswers, null, null);
    }

    public LearningPlan submit(UUID userId, UUID planId, List<String> rawAnswers, Integer requestedRound, UUID requestId) {
        LearningPlan plan = get(userId, planId);
        if (hasSubmission(plan, requestId)) return plan;
        if (requestedRound != null && requestedRound != plan.getAttemptCount())
            throw new IllegalStateException("学习轮次已更新，请刷新后重新提交");
        if (plan.getStatus() == LearningStatus.COMPLETED) {
            throw new IllegalStateException("这项学习已经完成");
        }
        List<String> answers = rawAnswers == null ? List.of()
                : rawAnswers.stream().map(value -> value == null ? "" : value.strip()).toList();
        if (answers.size() != plan.getExercises().size() || answers.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("请完成全部练习后再提交");
        }
        int expectedAttemptCount = plan.getAttemptCount();
        Instant expectedSchedule = plan.getScheduledAt();
        List<Map<String, Object>> attempts = new ArrayList<>();
        for (int index = 0; index < answers.size(); index++) {
            Map<String, Object> exercise = plan.getExercises().get(index);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("question", exercise.get("question"));
            item.put("type", exercise.getOrDefault("type", "SHORT_ANSWER"));
            item.put("rubric", exercise.get("rubric"));
            item.put("answer", answers.get(index));
            if ("MULTIPLE_CHOICE".equals(exercise.get("type")) || "TRUE_FALSE".equals(exercise.get("type"))) {
                List<String> options = exercise.get("options") instanceof List<?> list
                        ? list.stream().map(String::valueOf).toList() : List.of();
                item.put("options", options);
                // Score the choice here instead of asking the model to judge a selection.
                if (exercise.get("correctOption") instanceof Number correctIndex
                        && correctIndex.intValue() >= 0 && correctIndex.intValue() < options.size()) {
                    String correct = options.get(correctIndex.intValue());
                    item.put("correctOption", correct);
                    item.put("choiceCorrect", correct.equals(answers.get(index)));
                }
            }
            attempts.add(item);
        }
        LlmGateway.LlmResult result = llmGateway.completeJson(userId, evaluationPrompt(),
                "学习主题：" + plan.getConcept() + "\n练习与回答：" + json(attempts));
        JsonNode node = jsonParser.parseObject(result.content());
        int modelScore = node.path("score").asInt(-1);
        // When the whole exercise set is multiple choice, the score is arithmetic —
        // asking the model to re-judge selections only invites invented deductions.
        int gradedScore = attempts.stream().allMatch(item -> item.containsKey("choiceCorrect"))
                ? (int) Math.round(100.0 * attempts.stream()
                        .filter(item -> Boolean.TRUE.equals(item.get("choiceCorrect"))).count() / attempts.size())
                : modelScore;
        if (gradedScore < 0 || gradedScore > 100) throw new IllegalStateException("模型没有返回有效练习分数");
        // The model call runs without a database lock. Recheck under a row lock and
        // commit the feedback together with its calendar state in one transaction.
        return transactions.execute(status -> commitAttempt(userId, planId, expectedAttemptCount,
                answers, node, gradedScore, requestId, expectedSchedule));
    }

    private boolean hasSubmission(LearningPlan plan, UUID requestId) {
        if (requestId == null || !(plan.getLastEvaluation().get("attempts") instanceof List<?> attempts)) return false;
        return attempts.stream().anyMatch(item -> item instanceof Map<?, ?> map
                && requestId.toString().equals(map.get("requestId")));
    }

    private LearningPlan commitAttempt(UUID userId, UUID planId, int expectedAttemptCount,
            List<String> answers, JsonNode node, int score, UUID requestId, Instant expectedSchedule) {
        LearningPlan plan = repository.findOwnedForUpdate(planId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("学习计划不存在"));
        if (hasSubmission(plan, requestId)) return plan;
        if (plan.getStatus() == LearningStatus.COMPLETED || plan.getAttemptCount() != expectedAttemptCount) {
            throw new IllegalStateException("学习内容已更新，请刷新后重新提交");
        }
        Map<String, Object> evaluation = new LinkedHashMap<>(
                objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() {}));
        // The next round's rubric is for grading only; never expose it through lastEvaluation.
        evaluation.remove("nextExercises");
        List<Object> history = new ArrayList<>();
        if (plan.getLastEvaluation().get("attempts") instanceof List<?> previous) {
            history.addAll(previous.subList(Math.max(0, previous.size() - 19), previous.size()));
        }
        List<Map<String, Object>> answeredItems = new ArrayList<>();
        for (int index = 0; index < answers.size(); index++) {
            Map<String, Object> exercise = plan.getExercises().get(index);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("question", exercise.get("question"));
            item.put("type", exercise.getOrDefault("type", "SHORT_ANSWER"));
            item.put("options", exercise.getOrDefault("options", List.of()));
            item.put("answer", answers.get(index));
            answeredItems.add(item);
        }
        Map<String, Object> recordedAttempt = new LinkedHashMap<>();
        recordedAttempt.put("items", answeredItems);
        if (requestId != null) recordedAttempt.put("requestId", requestId.toString());
        recordedAttempt.put("score", score);
        recordedAttempt.put("submittedAt", Instant.now().toString());
        recordedAttempt.put("feedback", node.path("feedback").asText(""));
        recordedAttempt.put("questionFeedback", evaluation.getOrDefault("questionFeedback", List.of()));
        history.add(recordedAttempt);
        evaluation.put("attempts", history);
        List<Map<String, Object>> followUps = score >= 80 ? List.of() : optionalExercises(node.path("nextExercises"));
        Instant nextReview = score >= 80 ? null : java.util.Objects.equals(plan.getScheduledAt(), expectedSchedule)
                ? tomorrowMorning(userId) : plan.getScheduledAt();
        plan.recordAttempt(score, evaluation, nextReview, followUps);
        LearningPlan saved = repository.save(plan);
        syncCalendar(saved);
        return saved;
    }

    @Transactional
    public void delete(UUID userId, UUID planId) {
        LearningPlan plan = get(userId, planId);
        creationStore.markDeleted(plan);
        repository.delete(plan);
    }

    private Map<String, Object> lesson(JsonNode node) {
        String summary = node.path("summary").asText().strip();
        String explanation = node.path("explanation").asText().strip();
        if (summary.isBlank() || explanation.isBlank()) throw new IllegalStateException("模型返回的学习内容不完整");
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("summary", summary);
        value.put("keyPoints", strings(node.path("keyPoints"), 3));
        value.put("explanation", explanation);
        value.put("example", node.path("example").asText().strip());
        value.put("pitfalls", strings(node.path("pitfalls"), 0));
        return value;
    }

    List<Map<String, Object>> exercises(JsonNode nodes) {
        List<Map<String, Object>> values = new ArrayList<>();
        if (nodes.isArray()) for (JsonNode node : nodes) {
            String question = node.path("question").asText().strip();
            List<String> rubric = strings(node.path("rubric"), 1);
            if (question.isBlank()) continue;
            String type = node.path("type").asText("SHORT_ANSWER").strip();
            if (!type.equals("MULTIPLE_CHOICE") && !type.equals("TRUE_FALSE")
                    && !type.equals("SCENARIO")) type = "SHORT_ANSWER";
            Map<String, Object> exercise = new LinkedHashMap<>();
            exercise.put("question", question);
            exercise.put("rubric", rubric);
            exercise.put("type", type);
            if (type.equals("MULTIPLE_CHOICE")) {
                List<String> options = strings(node.path("options"), 0);
                if (options.size() < 3 || options.size() > 5) continue;
                if (!node.path("options").isArray() || node.path("options").size() != options.size()
                        || new java.util.HashSet<>(options).size() != options.size()) continue;
                boolean textual = true;
                for (JsonNode option : node.path("options")) if (!option.isTextual()) textual = false;
                if (!textual) continue;
                exercise.put("options", options);
                JsonNode correct = node.path("correctOption");
                if (!correct.isInt() || correct.asInt() < 0 || correct.asInt() >= options.size()) continue;
                exercise.put("correctOption", correct.asInt());
            }
            if (type.equals("TRUE_FALSE")) {
                JsonNode answer = node.path("correctAnswer");
                if (!answer.isBoolean()) continue;
                exercise.put("options", List.of("正确", "错误"));
                exercise.put("correctOption", answer.asBoolean() ? 0 : 1);
            }
            values.add(exercise);
        }
        if (values.size() < 2) throw new IllegalStateException("模型返回的有效练习题少于 2 道");
        return values;
    }

    private List<Map<String, Object>> optionalExercises(JsonNode nodes) {
        try { return exercises(nodes); }
        catch (IllegalStateException exception) { return List.of(); }
    }

    private List<String> strings(JsonNode node, int minimum) {
        List<String> values = new ArrayList<>();
        if (node.isArray()) node.forEach(item -> {
            String value = item.asText().strip();
            if (!value.isBlank()) values.add(value);
        });
        if (values.size() < minimum) throw new IllegalStateException("模型返回的学习结构不完整");
        return values;
    }

    private void syncCalendar(LearningPlan plan) {
        Instant dueAt = plan.getStatus() == LearningStatus.NEEDS_REVIEW
                ? plan.getNextReviewAt() : plan.getScheduledAt();
        calendarTaskService.refreshLearningPlan(plan.getUserId(), plan.getId(),
                plan.getConcept(), plan.getReason(), dueAt,
                plan.getSourceType() == LearningSourceType.MANUAL
                        ? CalendarTaskSource.MANUAL : CalendarTaskSource.AGENT,
                plan.getStatus() == LearningStatus.COMPLETED);
    }

    private Instant tomorrowMorning(UUID userId) {
        ZoneId zone = ZoneId.of(profileService.get(userId).getTimezone());
        return LocalDate.now(zone).plusDays(1).atTime(LocalTime.of(9, 0)).atZone(zone).toInstant();
    }

    private String generationPrompt() {
        return """
                你是严谨的中文技术导师。只输出 JSON 对象，不要用代码围栏包住 JSON。
                格式：{"lesson":{"summary":"一句话","keyPoints":["..."],"explanation":"系统讲解",
                "example":"具体例子","pitfalls":["..."]},"exercises":[{"question":"练习题",
                "type":"SHORT_ANSWER 或 MULTIPLE_CHOICE 或 TRUE_FALSE 或 SCENARIO",
                "options":["选项一","选项二","选项三"],"correctOption":0,"correctAnswer":true,"rubric":["评分要点"]}]}。
                生成 3 到 5 个关键点和 3 到 6 道练习。讲解要准确、循序渐进、面向求职；
                explanation 和 example 的字符串使用 Markdown：短小的小标题、分点、关键词加粗，
                必要时使用代码块或表格。每段聚焦一个知识点，避免大段连续文字。summary 保持一句话。
                练习必须混合多种题型：至少一道 MULTIPLE_CHOICE（输出 options 和 correctOption，
                correctOption 是正确选项下标，从 0 开始）和至少一道 TRUE_FALSE（输出 correctAnswer 布尔值，
                不要输出 options）；其余可为 SHORT_ANSWER 或 SCENARIO。只有 MULTIPLE_CHOICE 需要 options；
                其他题型不要输出 options、correctOption 或 correctAnswer。
                练习必须检验理解与应用，不能只问定义，也不得虚构用户经历。
                练习必须检验理解与应用，不能只问定义，也不得虚构用户经历。
                """;
    }

    private String evaluationPrompt() {
        return """
                你是严格但有建设性的中文技术教练。只输出 JSON 对象，不要用代码围栏包住 JSON。
                格式：{"score":0,"feedback":"总体反馈","strengths":["..."],"gaps":["..."],
                "questionFeedback":[{"question":"原题","feedback":"针对该回答的解释"}],
                "nextFocus":"下一步重点","nextExercises":[{"question":"新题",
                "type":"SHORT_ANSWER 或 MULTIPLE_CHOICE 或 SCENARIO",
                "options":["选项一","选项二","选项三"],"rubric":["评分要点"]}]}。
                score 是 0 到 100 的整数，只能依据评分要点和实际回答评分。
                选择题（type=MULTIPLE_CHOICE）和判断题（type=TRUE_FALSE）的 options、标准答案和判定结果已由系统给出：
                带 choiceCorrect 的题必须以它为准，答对就按满分计，答错指出正确选项即可；
                不得因为用户没有解释选项、没有补充理由而扣分，也不要要求用户说明选择理由。
                其余题型按评分要点评估。
                feedback 和 questionFeedback.feedback 可用 Markdown 分点并加粗关键结论，避免冗长段落。
                如果得分低于 80，必须根据薄弱点出 3 到 5 道不同于原题的新题，题型要混合：
                至少一道 MULTIPLE_CHOICE（带 options 和 correctOption）、可以有 TRUE_FALSE（带 correctAnswer 布尔值），
                其余为 SHORT_ANSWER 或 SCENARIO；如果得分达到 80，nextExercises 返回空数组。
                只有 MULTIPLE_CHOICE 需要 options，其他题型不要输出 options。
                """;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (tools.jackson.core.JacksonException exception) {
            throw new IllegalStateException("无法构造练习评分输入", exception);
        }
    }

    private String required(String value, String message, int maxLength) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        String normalized = value.strip();
        if (normalized.length() > maxLength) throw new IllegalArgumentException(message);
        return normalized;
    }

    private String optional(String value, int maxLength) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip();
        if (normalized.length() > maxLength) throw new IllegalArgumentException("学习原因过长");
        return normalized;
    }

    private String requestHash(String concept, String reason, LearningSourceType source,
            UUID sourceId, Instant scheduledAt) {
        String input = framed(concept) + framed(reason) + framed(source.name())
                + framed(sourceId == null ? null : sourceId.toString())
                + framed(scheduledAt == null ? null : scheduledAt.toString());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private String framed(String value) {
        return value == null ? "-1:" : value.length() + ":" + value;
    }
}
