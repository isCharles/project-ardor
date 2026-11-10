package com.projectardor.learning.practice.mockinterview.service.adaptive;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.projectardor.common.json.LlmJsonParser;
import com.projectardor.common.web.ResourceNotFoundException;
import com.projectardor.jobsearch.interview.service.OpportunityService;
import com.projectardor.learning.practice.mockinterview.domain.InterviewMode;
import com.projectardor.learning.practice.mockinterview.domain.InterviewPlan;
import com.projectardor.learning.practice.mockinterview.domain.InterviewSession;
import com.projectardor.learning.practice.mockinterview.repository.InterviewPlanRepository;
import com.projectardor.learning.practice.mockinterview.repository.InterviewSessionRepository;
import com.projectardor.learning.practice.mockinterview.service.InterviewMetrics;
import com.projectardor.materials.resume.service.ResumeAnalysisSnapshot;
import com.projectardor.materials.resume.service.ResumeService;
import com.projectardor.platform.model.llm.service.LlmGateway;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns a resume and a job description into the competencies this interview has to cover. The model
 * proposes; Java decides how many survive, what order they are asked in and what a resume is allowed
 * to claim. A resume mention stays a hypothesis: it never becomes evidence of ability.
 */
@Service
public class InterviewPlanner {

    public static final String PROMPT_VERSION = "adaptive-interview-plan-v1";
    public static final int MIN_COMPETENCIES = 3;
    public static final int MAX_COMPETENCIES = 8;
    private static final int MAX_KNOWLEDGE_POINTS = 6;
    private static final java.util.Set<String> QUESTION_TYPES =
            java.util.Set.of("TECHNICAL", "PROJECT", "BEHAVIORAL", "CODING");

    private static final String SYSTEM_PROMPT = """
            你是中文技术面试的能力规划师。只输出 JSON 对象，不要 Markdown。
            格式必须为 {"competencies":[{"skill":"能力维度名","jdPriority":1-5,"initialDifficulty":1-5,
            "questionType":"TECHNICAL|PROJECT|BEHAVIORAL|CODING","knowledgePoints":["具体知识点"],
            "resumeMentioned":true|false,"rationale":"为什么这个岗位要考它"}]}。
            jdPriority：5 是岗位核心要求，1 是加分项。initialDifficulty：1 概念，2 机制，3 应用与常见问题，
            4 深层原理与取舍，5 复杂场景与系统设计。
            简历只能作为初始假设：简历写到某项技能时把 resumeMentioned 设为 true，但这不代表候选人已掌握。
            不要编造简历里没有的经历，不要输出岗位描述之外的无关能力。
            """;

    private final InterviewSessionRepository sessions;
    private final InterviewPlanRepository plans;
    private final ResumeService resumes;
    private final OpportunityService opportunities;
    private final LlmGateway llmGateway;
    private final LlmJsonParser jsonParser;
    private final ObjectMapper objectMapper;
    private final InterviewMetrics metrics;
    private final TransactionTemplate transactions;

    public InterviewPlanner(
            InterviewSessionRepository sessions,
            InterviewPlanRepository plans,
            ResumeService resumes,
            OpportunityService opportunities,
            LlmGateway llmGateway,
            LlmJsonParser jsonParser,
            ObjectMapper objectMapper,
            InterviewMetrics metrics,
            org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.sessions = sessions;
        this.plans = plans;
        this.resumes = resumes;
        this.opportunities = opportunities;
        this.llmGateway = llmGateway;
        this.jsonParser = jsonParser;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public record CompetencyTarget(String skill, int jdPriority, int initialDifficulty, String questionType,
            List<String> knowledgePoints, boolean resumeMentioned, String rationale) {
        public CompetencyTarget {
            knowledgePoints = List.copyOf(knowledgePoints);
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("skill", skill);
            map.put("jdPriority", jdPriority);
            map.put("initialDifficulty", initialDifficulty);
            map.put("questionType", questionType);
            map.put("knowledgePoints", knowledgePoints);
            map.put("resumeMentioned", resumeMentioned);
            map.put("rationale", rationale);
            return map;
        }
    }

    public record CompetencyPlan(List<CompetencyTarget> competencies, String modelName, String promptVersion) {
        public CompetencyPlan {
            competencies = List.copyOf(competencies);
        }
    }

    /** Idempotent: an interrupted interview reloads the plan it already paid for instead of regenerating it. */
    public CompetencyPlan plan(UUID userId, UUID sessionId) {
        InterviewSession session = adaptiveSession(userId, sessionId);
        var existing = plans.findByInterviewSessionIdAndUserId(sessionId, userId);
        if (existing.isPresent()) return read(existing.get());

        ResumeAnalysisSnapshot resume = session.getResumeAnalysisId() == null
                ? null
                : resumes.analysisContext(userId, session.getResumeAnalysisId());
        String userPrompt = "请规划这场自适应面试的能力维度：\n" + toJson(Map.of(
                "targetRole", session.getTargetRole(),
                "targetCompany", session.getTargetCompany() == null ? "" : session.getTargetCompany(),
                "seniority", session.getSeniority() == null ? "" : session.getSeniority(),
                "durationMinutes", session.getDurationMinutes() == null ? 0 : session.getDurationMinutes(),
                "jobDescription", jobDescription(userId, session),
                "resumeAnalysis", resume == null || resume.analysis() == null ? Map.of() : resume.analysis()));

        LlmGateway.LlmResult result = llmGateway.completeJson(userId, SYSTEM_PROMPT, userPrompt);
        metrics.recordTokens("plan", result.usage());
        List<CompetencyTarget> competencies;
        try {
            competencies = parse(jsonParser.parseObject(result.content()));
        } catch (IllegalStateException invalid) {
            result = llmGateway.completeJson(userId,
                    SYSTEM_PROMPT + "\n上一次输出不合格，请重新输出完整合格的能力规划。",
                    userPrompt + "\n不合格原因：" + invalid.getMessage());
            metrics.recordTokens("plan", result.usage());
            competencies = parse(jsonParser.parseObject(result.content()));
        }
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("competencies", competencies.stream().map(CompetencyTarget::toMap).toList());
        String modelName = result.model();
        return transactions.execute(status -> {
            var saved = plans.findByInterviewSessionIdAndUserId(sessionId, userId);
            if (saved.isPresent()) return read(saved.get());
            return read(plans.save(InterviewPlan.create(userId, sessionId, stored, modelName, PROMPT_VERSION)));
        });
    }

    public CompetencyPlan require(UUID userId, UUID sessionId) {
        adaptiveSession(userId, sessionId);
        return read(plans.findByInterviewSessionIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("该面试尚未生成能力规划")));
    }

    private InterviewSession adaptiveSession(UUID userId, UUID sessionId) {
        InterviewSession session = sessions.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("模拟面试不存在"));
        if (session.getMode() != InterviewMode.ADAPTIVE) {
            throw new IllegalStateException("固定题目面试没有能力规划");
        }
        return session;
    }

    private String jobDescription(UUID userId, InterviewSession session) {
        if (session.getJobDescription() != null && !session.getJobDescription().isBlank()) {
            return session.getJobDescription();
        }
        if (session.getJobOpportunityId() == null) return "";
        String content = opportunities.detail(userId, session.getJobOpportunityId()).jdContent();
        return content == null ? "" : content;
    }

    private CompetencyPlan read(InterviewPlan plan) {
        Object raw = plan.getPlan().get("competencies");
        List<Map<String, Object>> entries = new ArrayList<>();
        if (raw instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof Map<?, ?> map) {
                    entries.add(objectMapper.convertValue(map, new TypeReference<>() {}));
                }
            }
        }
        return new CompetencyPlan(normalize(entries), plan.getModelName(), plan.getPromptVersion());
    }

    private List<CompetencyTarget> parse(JsonNode root) {
        JsonNode nodes = root.path("competencies");
        if (!nodes.isArray()) throw new IllegalStateException("未返回能力维度数组");
        return normalize(objectMapper.convertValue(nodes, new TypeReference<List<Map<String, Object>>>() {}));
    }

    /** One normalizer for both the model's answer and the stored plan, so a reload cannot drift. */
    private List<CompetencyTarget> normalize(List<Map<String, Object>> entries) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<CompetencyTarget> targets = new ArrayList<>();
        for (Map<String, Object> entry : entries) {
            String skill = text(entry.get("skill"));
            if (skill.isBlank()) continue;
            if (!seen.add(skill.toLowerCase(Locale.ROOT).replaceAll("\\s+", " "))) continue;
            List<String> points = knowledgePoints(entry.get("knowledgePoints"));
            if (points.isEmpty()) continue;
            String type = text(entry.get("questionType")).toUpperCase(Locale.ROOT);
            targets.add(new CompetencyTarget(skill, clamp(entry.get("jdPriority")),
                    clamp(entry.get("initialDifficulty")),
                    QUESTION_TYPES.contains(type) ? type : "TECHNICAL",
                    points, Boolean.TRUE.equals(entry.get("resumeMentioned"))
                            || "true".equalsIgnoreCase(text(entry.get("resumeMentioned"))),
                    text(entry.get("rationale"))));
            if (targets.size() == MAX_COMPETENCIES) break;
        }
        if (targets.size() < MIN_COMPETENCIES) {
            throw new IllegalStateException("有效能力维度不足（" + targets.size() + "/" + MIN_COMPETENCIES
                    + "），每个维度都要有名称和至少一个知识点");
        }
        // Java owns the asking order, so the same plan always schedules the same way.
        targets.sort(Comparator.comparingInt(CompetencyTarget::jdPriority).reversed()
                .thenComparing(CompetencyTarget::skill));
        return List.copyOf(targets);
    }

    private static List<String> knowledgePoints(Object value) {
        if (!(value instanceof List<?> items)) return List.of();
        LinkedHashSet<String> points = new LinkedHashSet<>();
        for (Object item : items) {
            String point = text(item);
            if (point.isBlank() || point.length() > 200) continue;
            points.add(point);
            if (points.size() == MAX_KNOWLEDGE_POINTS) break;
        }
        return List.copyOf(points);
    }

    private static int clamp(Object value) {
        int parsed = 3;
        if (value instanceof Number number) parsed = number.intValue();
        else if (value != null) {
            try {
                parsed = Integer.parseInt(String.valueOf(value).strip());
            } catch (NumberFormatException ignored) {
                parsed = 3;
            }
        }
        return Math.max(1, Math.min(5, parsed));
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("无法构造 LLM 输入", exception);
        }
    }
}