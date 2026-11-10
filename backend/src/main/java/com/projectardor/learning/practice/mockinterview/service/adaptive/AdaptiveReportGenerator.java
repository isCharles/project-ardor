package com.projectardor.learning.practice.mockinterview.service.adaptive;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.projectardor.common.json.LlmJsonParser;
import com.projectardor.learning.practice.mockinterview.service.InterviewMetrics;
import com.projectardor.platform.model.llm.service.LlmGateway;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes the report from evidence Java already holds. The competency assessment and the knowledge
 * boundary are built from the persisted profile, not from the model's memory of the interview; the
 * model only writes the prose and proposes advice for gaps that were actually recorded. Java drops
 * advice with no matching evidence and orders what survives by JD priority times gap severity.
 */
@Service
public class AdaptiveReportGenerator {

    public static final String PROMPT_VERSION = "adaptive-interview-report-v1";
    private static final int MAX_TEXT_LENGTH = 2000;
    private static final int MAX_RECOMMENDATIONS = 12;

    private static final String SYSTEM_PROMPT = """
            你是中文技术面试评价官。只输出 JSON 对象，不要 Markdown。
            格式必须为 {"overallScore":0-100 的数字,"jobFit":"...","levelAssessment":"...","performanceSummary":"...",
            "learningRecommendations":[{"competency":"...","knowledgePoint":"...","severity":1-5,"advice":"...","evidence":"..."}]}。
            只能依据给出的能力画像、知识边界和答题记录作答，不得编造候选人经历或题目。
            learningRecommendations 只能针对 knowledgeBoundary 里已经列出的知识点，每条的 evidence 必须引用记录中的具体回答表现。
            未验证（UNVERIFIED）的能力表示尚未考察，不能写成候选人不会。
            建议要具体、可执行，说明练什么、练到什么程度，不要泛泛而谈。
            """;

    private final LlmGateway llmGateway;
    private final LlmJsonParser jsonParser;
    private final ObjectMapper objectMapper;
    private final InterviewMetrics metrics;

    public AdaptiveReportGenerator(LlmGateway llmGateway, LlmJsonParser jsonParser, ObjectMapper objectMapper,
            InterviewMetrics metrics) {
        this.llmGateway = llmGateway;
        this.jsonParser = jsonParser;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    public record Input(String targetRole, String targetCompany, String seniority,
            List<CompetencyProfileManager.Snapshot> profiles, List<Map<String, Object>> transcript) {
        public Input {
            profiles = List.copyOf(profiles);
            transcript = List.copyOf(transcript);
        }
    }

    public record Report(BigDecimal overallScore, Map<String, Object> content, String modelName) {}

    public Report generate(UUID userId, Input input) {
        List<Map<String, Object>> competencyAssessments = competencyAssessments(input.profiles());
        List<Map<String, Object>> boundary = knowledgeBoundary(input.profiles());
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("targetRole", nullToEmpty(input.targetRole()));
        evidence.put("targetCompany", nullToEmpty(input.targetCompany()));
        evidence.put("seniority", nullToEmpty(input.seniority()));
        evidence.put("competencyAssessments", competencyAssessments);
        evidence.put("knowledgeBoundary", boundary);
        evidence.put("transcript", input.transcript());
        String userPrompt = "请基于以下面试证据写报告：\n" + toJson(evidence);

        LlmGateway.LlmResult result = llmGateway.completeJson(userId, SYSTEM_PROMPT, userPrompt);
        metrics.recordTokens("report", result.usage());
        Parsed parsed;
        try {
            parsed = parse(jsonParser.parseObject(result.content()), input.profiles());
        } catch (IllegalStateException invalid) {
            result = llmGateway.completeJson(userId,
                    SYSTEM_PROMPT + "\n上一次输出不合格，请重新输出完整合格的报告。",
                    userPrompt + "\n不合格原因：" + invalid.getMessage());
            metrics.recordTokens("report", result.usage());
            parsed = parse(jsonParser.parseObject(result.content()), input.profiles());
        }

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("reportKind", "ADAPTIVE");
        content.put("jobFit", parsed.jobFit());
        content.put("levelAssessment", parsed.levelAssessment());
        content.put("performanceSummary", parsed.performanceSummary());
        content.put("competencyAssessments", competencyAssessments);
        content.put("knowledgeBoundary", boundary);
        content.put("learningRecommendations", parsed.recommendations());
        return new Report(parsed.overallScore(), content, result.model());
    }

    private record Parsed(BigDecimal overallScore, String jobFit, String levelAssessment, String performanceSummary,
            List<Map<String, Object>> recommendations) {}

    private Parsed parse(JsonNode root, List<CompetencyProfileManager.Snapshot> profiles) {
        if (!root.path("overallScore").isNumber()) throw new IllegalStateException("报告缺少有效的面试总分");
        BigDecimal score = root.path("overallScore").decimalValue().setScale(2, RoundingMode.HALF_UP);
        if (score.compareTo(BigDecimal.ZERO) < 0 || score.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalStateException("报告总分不在 0 到 100 之间");
        }
        String jobFit = requiredText(root, "jobFit");
        String levelAssessment = requiredText(root, "levelAssessment");
        String performanceSummary = requiredText(root, "performanceSummary");

        Set<String> recordedGaps = new LinkedHashSet<>();
        Map<String, Integer> priorities = new LinkedHashMap<>();
        for (CompetencyProfileManager.Snapshot profile : profiles) {
            priorities.put(profile.skill(), profile.jdPriority());
            for (Map<String, Object> gap : profile.knowledgeGaps()) {
                recordedGaps.add(gapKey(profile.skill(), String.valueOf(gap.get("knowledgePoint"))));
            }
        }

        List<Map<String, Object>> recommendations = new ArrayList<>();
        JsonNode items = root.path("learningRecommendations");
        if (items.isArray()) {
            for (JsonNode item : items) {
                String competency = item.path("competency").asText("").strip();
                String knowledgePoint = item.path("knowledgePoint").asText("").strip();
                // Advice without a recorded gap is generic advice, which is exactly what the report must not contain.
                if (!recordedGaps.contains(gapKey(competency, knowledgePoint))) continue;
                String advice = item.path("advice").asText("").strip();
                String evidence = item.path("evidence").asText("").strip();
                if (advice.isBlank() || evidence.isBlank()) continue;
                Map<String, Object> recommendation = new LinkedHashMap<>();
                recommendation.put("competency", competency);
                recommendation.put("knowledgePoint", knowledgePoint);
                recommendation.put("jdPriority", priorities.getOrDefault(competency, 3));
                recommendation.put("severity", severity(item.path("severity").asInt(3)));
                recommendation.put("advice", bounded(advice));
                recommendation.put("evidence", bounded(evidence));
                recommendations.add(recommendation);
            }
        }
        recommendations.sort(Comparator
                .comparingInt((Map<String, Object> item) -> (Integer) item.get("jdPriority")).reversed()
                .thenComparing(Comparator.comparingInt((Map<String, Object> item) -> (Integer) item.get("severity"))
                        .reversed())
                .thenComparing(item -> String.valueOf(item.get("competency")))
                .thenComparing(item -> String.valueOf(item.get("knowledgePoint"))));
        if (recommendations.size() > MAX_RECOMMENDATIONS) {
            recommendations = new ArrayList<>(recommendations.subList(0, MAX_RECOMMENDATIONS));
        }
        return new Parsed(score, jobFit, levelAssessment, performanceSummary, List.copyOf(recommendations));
    }

    private static List<Map<String, Object>> competencyAssessments(List<CompetencyProfileManager.Snapshot> profiles) {
        List<Map<String, Object>> assessments = new ArrayList<>();
        for (CompetencyProfileManager.Snapshot profile : profiles) {
            Map<String, Object> assessment = new LinkedHashMap<>();
            assessment.put("competency", profile.skill());
            assessment.put("jdPriority", profile.jdPriority());
            assessment.put("estimatedLevel", profile.estimatedLevel());
            assessment.put("confidence", profile.confidence());
            assessment.put("evidenceCount", profile.evidenceCount());
            assessment.put("state", profile.state().name());
            assessment.put("strengths", profile.strengths());
            assessment.put("knowledgeGaps", profile.knowledgeGaps());
            assessments.add(assessment);
        }
        return assessments;
    }

    private static List<Map<String, Object>> knowledgeBoundary(List<CompetencyProfileManager.Snapshot> profiles) {
        List<Map<String, Object>> boundary = new ArrayList<>();
        for (CompetencyProfileManager.Snapshot profile : profiles) {
            for (Map<String, Object> gap : profile.knowledgeGaps()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("competency", profile.skill());
                entry.put("knowledgePoint", gap.get("knowledgePoint"));
                entry.put("difficulty", gap.get("difficulty"));
                entry.put("detail", gap.get("detail"));
                boundary.add(entry);
            }
        }
        return boundary;
    }

    private static String gapKey(String competency, String knowledgePoint) {
        return competency + "\u0000" + knowledgePoint;
    }

    private static int severity(int proposed) {
        return Math.max(1, Math.min(5, proposed));
    }

    private static String requiredText(JsonNode root, String field) {
        String value = root.path(field).asText("").strip();
        if (value.isBlank()) throw new IllegalStateException("报告缺少 " + field);
        return bounded(value);
    }

    private static String bounded(String value) {
        return value.length() <= MAX_TEXT_LENGTH ? value : value.substring(0, MAX_TEXT_LENGTH);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("无法构造报告输入", exception);
        }
    }
}