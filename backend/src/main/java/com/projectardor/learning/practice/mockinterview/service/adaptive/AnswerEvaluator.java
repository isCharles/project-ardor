package com.projectardor.learning.practice.mockinterview.service.adaptive;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.projectardor.common.json.LlmJsonParser;
import com.projectardor.learning.practice.mockinterview.domain.AdaptiveRubric;
import com.projectardor.learning.practice.mockinterview.domain.AnswerKind;
import com.projectardor.learning.practice.mockinterview.domain.InterviewQuestion;
import com.projectardor.platform.model.llm.service.LlmGateway;

import tools.jackson.databind.JsonNode;

/**
 * Scores one answer against the rubric that was stored before the question was asked. The model may
 * only claim coverage of numbered points from that rubric, and every claim has to quote the answer
 * verbatim; Java works out what was missed. A score of 3 or 4 is refused unless every expected point
 * was actually covered, so the band text means the same thing on every turn.
 */
@Service
public class AnswerEvaluator {

    public static final String PROMPT_VERSION = "adaptive-answer-evaluation-v1";
    private static final int MIN_QUOTE_LENGTH = 4;
    private static final int MAX_QUOTE_LENGTH = 400;
    private static final int MAX_FINDINGS = 12;

    private static final String SYSTEM_PROMPT = """
            你是严谨的中文技术面试评分官。只输出 JSON 对象，不要 Markdown。
            格式必须为 {"answerKind":"ANSWER|CLARIFICATION|OFF_TOPIC|UNKNOWN","score":0-4,"confidence":0.0-1.0,
            "covered":[{"point":必答要点下标,"quote":"答案中逐字复制的原文片段"}],
            "depthQuote":"体现深入理解的原文片段，没有则空字符串",
            "misconceptions":["答案中出现的错误认识"],"knowledgeGaps":["答案暴露的知识缺口"]}。
            只能依据给出的评分标准打分，不许自创标准，不许因为答案风格或篇幅加减分。
            covered 里的 point 必须是必答要点列表里的下标，quote 必须从答案里逐字复制，不得改写或概括。
            answerKind：候选人反问题目本身是 CLARIFICATION；答非所问是 OFF_TOPIC；明确说不会/不知道是 UNKNOWN；
            其余是 ANSWER。CLARIFICATION 不打分。UNKNOWN 的 score 必须是 0。
            confidence 表示你对这次判断的确信程度：答案含糊、过短或无法判断时给低分，不要靠猜测补全候选人的意思。
            """;

    private final LlmGateway llmGateway;
    private final LlmJsonParser jsonParser;

    public AnswerEvaluator(LlmGateway llmGateway, LlmJsonParser jsonParser) {
        this.llmGateway = llmGateway;
        this.jsonParser = jsonParser;
    }

    public record PointEvidence(String point, String quote) {}

    public record Evaluation(AnswerKind kind, Integer score, BigDecimal confidence,
            List<PointEvidence> coveredPoints, List<String> missingPoints, List<String> misconceptions,
            List<String> knowledgeGaps, String depthQuote, String modelName, String promptVersion) {

        public Evaluation {
            coveredPoints = List.copyOf(coveredPoints);
            missingPoints = List.copyOf(missingPoints);
            misconceptions = List.copyOf(misconceptions);
            knowledgeGaps = List.copyOf(knowledgeGaps);
        }

        public boolean isClarification() {
            return kind == AnswerKind.CLARIFICATION;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("answerKind", kind.name());
            map.put("score", score);
            map.put("confidence", confidence);
            map.put("coveredPoints", coveredPoints.stream()
                    .map(evidence -> Map.of("point", evidence.point(), "quote", evidence.quote())).toList());
            map.put("missingPoints", missingPoints);
            map.put("misconceptions", misconceptions);
            map.put("knowledgeGaps", knowledgeGaps);
            map.put("depthQuote", depthQuote == null ? "" : depthQuote);
            return map;
        }
    }

    public Evaluation evaluate(UUID userId, InterviewQuestion question, String answerText) {
        if (question.getRubric() == null) {
            throw new IllegalStateException("该题没有事先定义的评分标准，无法评分");
        }
        AdaptiveRubric rubric = AdaptiveRubric.fromMap(question.getRubric());
        if (answerText == null || answerText.isBlank()) throw new IllegalArgumentException("答案不能为空");
        String userPrompt = userPrompt(question, rubric, answerText);
        LlmGateway.LlmResult result = llmGateway.completeJson(userId, SYSTEM_PROMPT, userPrompt);
        try {
            return parse(jsonParser.parseObject(result.content()), rubric, answerText, result.model());
        } catch (IllegalStateException | IllegalArgumentException invalid) {
            result = llmGateway.completeJson(userId,
                    SYSTEM_PROMPT + "\n上一次输出不合格，请重新评分。quote 必须逐字来自答案原文。",
                    userPrompt + "\n不合格原因：" + invalid.getMessage());
            return parse(jsonParser.parseObject(result.content()), rubric, answerText, result.model());
        }
    }

    private String userPrompt(InterviewQuestion question, AdaptiveRubric rubric, String answerText) {
        StringBuilder prompt = new StringBuilder("题目：").append(question.getQuestionText())
                .append("\n知识点：").append(rubric.knowledgePoint())
                .append("\n难度：").append(rubric.difficulty()).append("（")
                .append(AdaptiveRubric.DIFFICULTY_LEVELS.get(rubric.difficulty() - 1)).append("）")
                .append("\n评分分档：");
        for (String band : rubric.scoreBands()) prompt.append("\n").append(band);
        prompt.append("\n必答要点：");
        for (int index = 0; index < rubric.expectedPoints().size(); index++) {
            prompt.append("\n").append(index).append(". ").append(rubric.expectedPoints().get(index));
        }
        if (!rubric.depthMarkers().isEmpty()) {
            prompt.append("\n深度标志：");
            for (String marker : rubric.depthMarkers()) prompt.append("\n- ").append(marker);
        }
        if (!rubric.commonMisconceptions().isEmpty()) {
            prompt.append("\n该知识点的常见误解（仅供识别，不代表候选人一定犯了）：");
            for (String misconception : rubric.commonMisconceptions()) prompt.append("\n- ").append(misconception);
        }
        return prompt.append("\n候选人答案：\n").append(answerText).toString();
    }

    private Evaluation parse(JsonNode root, AdaptiveRubric rubric, String answerText, String modelName) {
        AnswerKind kind = kind(root.path("answerKind").asText(""));
        // A clarification is never scored, whatever the model decided to put in the score field.
        // Nothing was assessed, so it leaves no covered or missing points behind either.
        if (kind == AnswerKind.CLARIFICATION) {
            return new Evaluation(kind, null, null, List.of(), List.of(), List.of(), List.of(),
                    null, modelName, PROMPT_VERSION);
        }
        int score = score(root.path("score"));
        BigDecimal confidence = confidence(root.path("confidence"));
        if (kind == AnswerKind.UNKNOWN && score != 0) {
            throw new IllegalStateException("明确表示不会的回答只能记 0 分");
        }
        String normalizedAnswer = normalize(answerText);
        List<PointEvidence> covered = coverage(root.path("covered"), rubric, normalizedAnswer);
        if (score >= 3 && covered.size() != rubric.expectedPoints().size()) {
            throw new IllegalStateException("3 分及以上必须覆盖全部必答要点，当前只覆盖 "
                    + covered.size() + "/" + rubric.expectedPoints().size());
        }
        String depthQuote = root.path("depthQuote").asText("").strip();
        if (score == 4) {
            requireQuote(depthQuote, normalizedAnswer, "深度证据");
        } else {
            depthQuote = "";
        }
        Set<String> coveredText = new LinkedHashSet<>();
        covered.forEach(evidence -> coveredText.add(evidence.point()));
        List<String> missing = rubric.expectedPoints().stream().filter(point -> !coveredText.contains(point)).toList();
        List<String> gaps = findings(root.path("knowledgeGaps"));
        // "I don't know" is a recorded gap on this knowledge point, not a misconception to argue with.
        if (kind == AnswerKind.UNKNOWN && !gaps.contains(rubric.knowledgePoint())) {
            List<String> withPoint = new ArrayList<>(gaps);
            withPoint.add(0, rubric.knowledgePoint());
            gaps = withPoint;
        }
        return new Evaluation(kind, score, confidence, covered, missing,
                findings(root.path("misconceptions")), gaps, depthQuote, modelName, PROMPT_VERSION);
    }

    private List<PointEvidence> coverage(JsonNode node, AdaptiveRubric rubric, String normalizedAnswer) {
        if (!node.isArray()) return List.of();
        List<PointEvidence> covered = new ArrayList<>();
        Set<Integer> seen = new LinkedHashSet<>();
        for (JsonNode item : node) {
            JsonNode index = item.path("point");
            if (!index.isInt() || index.asInt() < 0 || index.asInt() >= rubric.expectedPoints().size()) {
                throw new IllegalStateException("covered 指向了评分标准之外的要点");
            }
            if (!seen.add(index.asInt())) throw new IllegalStateException("同一个必答要点被重复认领");
            String quote = item.path("quote").asText("").strip();
            requireQuote(quote, normalizedAnswer, "要点 " + index.asInt() + " 的证据");
            covered.add(new PointEvidence(rubric.expectedPoints().get(index.asInt()), quote));
        }
        return covered;
    }

    /** A claim the answer does not actually contain is not evidence, so the turn stays unscored and retryable. */
    private static void requireQuote(String quote, String normalizedAnswer, String label) {
        String normalized = normalize(quote);
        if (normalized.length() < MIN_QUOTE_LENGTH) throw new IllegalStateException(label + "过短，无法作为证据");
        if (normalized.length() > MAX_QUOTE_LENGTH) throw new IllegalStateException(label + "过长，请只引用关键片段");
        if (!normalizedAnswer.contains(normalized)) throw new IllegalStateException(label + "不是答案原文");
    }

    private static AnswerKind kind(String value) {
        try {
            return AnswerKind.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("无法识别的回答类型：" + value);
        }
    }

    private static int score(JsonNode node) {
        if (!node.isInt() || node.asInt() < 0 || node.asInt() > 4) {
            throw new IllegalStateException("分数必须是 0 到 4 的整数");
        }
        return node.asInt();
    }

    private static BigDecimal confidence(JsonNode node) {
        if (!node.isNumber()) throw new IllegalStateException("置信度必须是 0 到 1 的数字");
        BigDecimal value = node.decimalValue().setScale(2, RoundingMode.HALF_UP);
        if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalStateException("置信度必须在 0 到 1 之间");
        }
        return value;
    }

    private static List<String> findings(JsonNode node) {
        if (!node.isArray()) return List.of();
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (JsonNode item : node) {
            String value = item.asText("").strip();
            if (value.isBlank()) continue;
            values.add(value.length() <= 300 ? value : value.substring(0, 300));
            if (values.size() == MAX_FINDINGS) break;
        }
        return List.copyOf(values);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
}