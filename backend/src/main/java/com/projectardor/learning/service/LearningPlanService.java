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
import com.projectardor.recap.repository.InterviewRecapQuestionRepository;
import com.projectardor.recap.repository.InterviewRecapRepository;

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
    private final InterviewRecapRepository recapRepository;
    private final InterviewRecapQuestionRepository recapQuestionRepository;
    private final LearningPlanCreationStore creationStore;

    public LearningPlanService(LearningPlanRepository repository, LlmGateway llmGateway,
            LlmJsonParser jsonParser, ObjectMapper objectMapper,
            CalendarTaskService calendarTaskService, ProfileService profileService,
            InterviewRecapRepository recapRepository, InterviewRecapQuestionRepository recapQuestionRepository,
            LearningPlanCreationStore creationStore) {
        this.repository = repository;
        this.llmGateway = llmGateway;
        this.jsonParser = jsonParser;
        this.objectMapper = objectMapper;
        this.calendarTaskService = calendarTaskService;
        this.profileService = profileService;
        this.recapRepository = recapRepository;
        this.recapQuestionRepository = recapQuestionRepository;
        this.creationStore = creationStore;
    }

    public LearningPlan create(UUID userId, String rawConcept, String rawReason,
            LearningSourceType sourceType, UUID sourceId, Instant scheduledAt) {
        return create(userId, null, rawConcept, rawReason, sourceType, sourceId, scheduledAt);
    }

    public LearningPlan create(UUID userId, UUID requestId, String rawConcept, String rawReason,
            LearningSourceType sourceType, UUID sourceId, Instant scheduledAt) {
        String concept = required(rawConcept, "学习概念不能为空", 160);
        String reason = optional(rawReason, 4000);
        LearningSourceType source = sourceType == null ? LearningSourceType.MANUAL : sourceType;
        String requestHash = requestId == null ? null : requestHash(concept, reason, source, sourceId, scheduledAt);
        LearningPlan previous = creationStore.existing(userId, requestId, requestHash);
        if (previous != null) return previous;
        if (source == LearningSourceType.RECAP && sourceId != null
                && recapRepository.findByIdAndUserId(sourceId, userId).isEmpty()
                && recapQuestionRepository.findByIdAndUserId(sourceId, userId).isEmpty()) {
            throw new ResourceNotFoundException("面经来源不存在");
        }
        Instant schedule = scheduledAt == null ? tomorrowMorning(userId) : scheduledAt;
        LlmGateway.LlmResult result = llmGateway.completeJson(userId, generationPrompt(),
                "学习主题：" + concept + "\n安排原因：" + (reason == null ? "用户主动学习" : reason));
        JsonNode root = jsonParser.parseObject(result.content());
        Map<String, Object> lesson = lesson(root.path("lesson"));
        List<Map<String, Object>> exercises = exercises(root.path("exercises"));
        return creationStore.save(userId, requestId, LearningPlan.create(
                userId, requestId, requestHash, concept, reason, source, sourceId, lesson, exercises, schedule, result.model()));
    }

    @Transactional(readOnly = true)
    public List<LearningPlan> list(UUID userId) {
        return repository.findAllByUserIdOrderByScheduledAtAscCreatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public LearningPlan get(UUID userId, UUID planId) {
        return repository.findByIdAndUserId(planId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("学习计划不存在"));
    }

    @Transactional
    public LearningPlan begin(UUID userId, UUID planId) {
        LearningPlan plan = get(userId, planId);
        plan.markInProgress();
        return plan;
    }

    public LearningPlan submit(UUID userId, UUID planId, List<String> rawAnswers) {
        LearningPlan plan = get(userId, planId);
        if (plan.getStatus() == LearningStatus.COMPLETED) {
            throw new IllegalStateException("这项学习已经完成");
        }
        List<String> answers = rawAnswers == null ? List.of()
                : rawAnswers.stream().map(value -> value == null ? "" : value.strip()).toList();
        if (answers.size() != plan.getExercises().size() || answers.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("请完成全部练习后再提交");
        }
        List<Map<String, Object>> attempts = new ArrayList<>();
        for (int index = 0; index < answers.size(); index++) {
            Map<String, Object> exercise = plan.getExercises().get(index);
            attempts.add(Map.of("question", exercise.get("question"),
                    "rubric", exercise.get("rubric"), "answer", answers.get(index)));
        }
        LlmGateway.LlmResult result = llmGateway.completeJson(userId, evaluationPrompt(),
                "学习主题：" + plan.getConcept() + "\n练习与回答：" + json(attempts));
        JsonNode node = jsonParser.parseObject(result.content());
        int score = node.path("score").asInt(-1);
        if (score < 0 || score > 100) throw new IllegalStateException("模型没有返回有效练习分数");
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
        recordedAttempt.put("score", score);
        recordedAttempt.put("submittedAt", Instant.now().toString());
        recordedAttempt.put("feedback", node.path("feedback").asText(""));
        recordedAttempt.put("questionFeedback", evaluation.getOrDefault("questionFeedback", List.of()));
        history.add(recordedAttempt);
        evaluation.put("attempts", history);
        List<Map<String, Object>> followUps = score >= 80 ? List.of() : optionalExercises(node.path("nextExercises"));
        plan.recordAttempt(score, evaluation, score >= 80 ? null : tomorrowMorning(userId), followUps);
        LearningPlan saved = repository.save(plan);
        syncCalendar(saved);
        return saved;
    }

    @Transactional
    public void delete(UUID userId, UUID planId) {
        repository.delete(get(userId, planId));
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

    private List<Map<String, Object>> exercises(JsonNode nodes) {
        List<Map<String, Object>> values = new ArrayList<>();
        if (nodes.isArray()) for (JsonNode node : nodes) {
            String question = node.path("question").asText().strip();
            List<String> rubric = strings(node.path("rubric"), 1);
            if (question.isBlank()) continue;
            String type = node.path("type").asText("SHORT_ANSWER").strip();
            if (!type.equals("MULTIPLE_CHOICE") && !type.equals("SCENARIO")) type = "SHORT_ANSWER";
            Map<String, Object> exercise = new LinkedHashMap<>();
            exercise.put("question", question);
            exercise.put("rubric", rubric);
            exercise.put("type", type);
            if (type.equals("MULTIPLE_CHOICE")) {
                List<String> options = strings(node.path("options"), 0);
                if (options.size() < 3 || options.size() > 5) continue;
                exercise.put("options", options);
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
                你是严谨的中文技术导师。只输出 JSON 对象，不要 Markdown。
                格式：{"lesson":{"summary":"一句话","keyPoints":["..."],"explanation":"系统讲解",
                "example":"具体例子","pitfalls":["..."]},"exercises":[{"question":"练习题",
                "type":"SHORT_ANSWER 或 MULTIPLE_CHOICE 或 SCENARIO",
                "options":["选项一","选项二","选项三"],"rubric":["评分要点"]}]}。
                生成 3 到 5 个关键点和 2 到 4 道练习。讲解要准确、循序渐进、面向求职；
                练习至少有一道 MULTIPLE_CHOICE 选择题，其余可为简答或情境题。只有选择题需要 options。
                练习必须检验理解与应用，不能只问定义，也不得虚构用户经历。
                """;
    }

    private String evaluationPrompt() {
        return """
                你是严格但有建设性的中文技术教练。只输出 JSON 对象，不要 Markdown。
                格式：{"score":0,"feedback":"总体反馈","strengths":["..."],"gaps":["..."],
                "questionFeedback":[{"question":"原题","feedback":"针对该回答的解释"}],
                "nextFocus":"下一步重点","nextExercises":[{"question":"新题",
                "type":"SHORT_ANSWER 或 MULTIPLE_CHOICE 或 SCENARIO",
                "options":["选项一","选项二","选项三"],"rubric":["评分要点"]}]}。
                score 是 0 到 100 的整数，只能依据评分要点和实际回答评分。
                如果得分低于 80，必须根据薄弱点出 2 到 4 道不同于原题的新题，其中至少一道选择题；
                如果得分达到 80，nextExercises 返回空数组。只有选择题需要 options。
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
