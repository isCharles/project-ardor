package com.projectardor.learning.practice.mockinterview.service.adaptive;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.projectardor.common.json.LlmJsonParser;
import com.projectardor.learning.practice.mockinterview.domain.AdaptiveRubric;
import com.projectardor.learning.practice.mockinterview.domain.InterviewQuestion;
import com.projectardor.learning.practice.mockinterview.domain.QuestionBlueprint;
import com.projectardor.learning.practice.mockinterview.domain.QuestionTarget;
import com.projectardor.learning.practice.mockinterview.repository.InterviewQuestionRepository;
import com.projectardor.learning.practice.mockinterview.service.InterviewMetrics;
import com.projectardor.learning.practice.mockinterview.service.LeetCodeHot100;
import com.projectardor.platform.model.llm.service.LlmGateway;

import tools.jackson.databind.JsonNode;

/**
 * Writes one question against a target the policy already chose. The model supplies the wording and
 * the points this particular question expects; Java supplies the score bands and refuses any output
 * that drifts from the target. Nothing is persisted here: the caller saves the question and the
 * decision that produced it together, so a crash cannot leave a question nobody decided to ask.
 */
@Service
public class AdaptiveQuestionService {

    public static final String PROMPT_VERSION = "adaptive-question-v1";
    private static final int MAX_QUESTION_LENGTH = 4000;

    private static final String SYSTEM_PROMPT = """
            你是中文技术面试官。只输出 JSON 对象，不要 Markdown。
            格式必须为 {"questionText":"完整、可直接回答的题目","expectedPoints":["必答要点"],
            "depthMarkers":["能把回答从正确推到深入的标志"],"commonMisconceptions":["该知识点的常见误解"]}。
            出题目标由系统决定，能力维度、知识点、难度和题型都不许改动。
            expectedPoints 给出 1-8 条这道题在该难度下必须答到的点，每条不超过 300 字，彼此不重复。
            depthMarkers 最多 6 条，描述达到深入理解时才会出现的内容。
            不要输出评分分档，分档由系统固定。不要编造候选人经历，题目本身不得包含答案。
            """;

    private final LlmGateway llmGateway;
    private final LlmJsonParser jsonParser;
    private final InterviewQuestionRepository questions;
    private final LeetCodeHot100 hot100;
    private final InterviewMetrics metrics;

    public AdaptiveQuestionService(
            LlmGateway llmGateway,
            LlmJsonParser jsonParser,
            InterviewQuestionRepository questions,
            LeetCodeHot100 hot100,
            InterviewMetrics metrics) {
        this.llmGateway = llmGateway;
        this.jsonParser = jsonParser;
        this.questions = questions;
        this.hot100 = hot100;
        this.metrics = metrics;
    }

    public record GeneratedQuestion(String questionText, String questionType, List<String> evaluationCriteria,
            QuestionBlueprint blueprint, String leetcodeSlug) {
        public GeneratedQuestion {
            evaluationCriteria = List.copyOf(evaluationCriteria);
        }

        public InterviewQuestion toEntity(UUID userId, UUID sessionId, int sequenceNumber) {
            return InterviewQuestion.createAdaptive(userId, sessionId, sequenceNumber, questionText,
                    questionType, evaluationCriteria, blueprint, leetcodeSlug);
        }
    }

    public GeneratedQuestion generate(UUID userId, UUID sessionId, QuestionTarget target) {
        long startedAt = System.nanoTime();
        GeneratedQuestion generated = "CODING".equals(target.questionType())
                ? coding(userId, sessionId, target)
                : written(userId, target);
        metrics.recordQuestion(target.questionType(), target.origin().name(), target.difficulty(),
                System.nanoTime() - startedAt);
        return generated;
    }

    private GeneratedQuestion written(UUID userId, QuestionTarget target) {
        String userPrompt = "请出一道题：\n"
                + "能力维度：" + target.competency() + "\n"
                + "知识点：" + target.knowledgePoint() + "\n"
                + "难度：" + target.difficulty() + "（"
                + AdaptiveRubric.DIFFICULTY_LEVELS.get(target.difficulty() - 1) + "）\n"
                + "题型：" + target.questionType() + "\n"
                + "来源：" + target.origin().name();
        LlmGateway.LlmResult result = llmGateway.completeJson(userId, SYSTEM_PROMPT, userPrompt);
        metrics.recordTokens("question", result.usage());
        try {
            return parse(jsonParser.parseObject(result.content()), target);
        } catch (IllegalStateException | IllegalArgumentException invalid) {
            result = llmGateway.completeJson(userId,
                    SYSTEM_PROMPT + "\n上一次输出不合格，请严格按出题目标重新输出。",
                    userPrompt + "\n不合格原因：" + invalid.getMessage());
            metrics.recordTokens("question", result.usage());
            return parse(jsonParser.parseObject(result.content()), target);
        }
    }

    /** Coding questions come from the curated bank, so the statement and the rubric are both known-good. */
    private GeneratedQuestion coding(UUID userId, UUID sessionId, QuestionTarget target) {
        LeetCodeHot100.Problem problem = unusedProblem(userId, sessionId);
        String text = "LeetCode " + problem.id() + ". " + problem.titleZh() + "（" + problem.titleEn() + "）："
                + "请在 LeetCode 完成这道题，然后把代码贴回来，并说明思路、时间与空间复杂度。";
        AdaptiveRubric rubric = AdaptiveRubric.create(target.knowledgePoint(), target.difficulty(),
                List.of("代码正确且覆盖边界情况", "说清解法选择的依据与整体思路", "给出准确的时间与空间复杂度"),
                target.difficulty() >= 4
                        ? List.of("对比替代解法并说明取舍", "指出最坏情况并给出优化方向")
                        : List.of("指出最坏情况并给出优化方向"),
                List.of("只给出能跑通的代码但说不清复杂度", "忽略空输入或重复元素等边界"));
        return new GeneratedQuestion(text, "CODING", rubric.expectedPoints(), target.withRubric(rubric),
                problem.slug());
    }

    private LeetCodeHot100.Problem unusedProblem(UUID userId, UUID sessionId) {
        Set<String> used = new HashSet<>();
        for (InterviewQuestion question : questions
                .findAllByUserIdAndInterviewSessionIdOrderBySequenceNumber(userId, sessionId)) {
            if (question.getLeetcodeSlug() != null) used.add(question.getLeetcodeSlug());
        }
        for (int attempt = 0; attempt < 8; attempt++) {
            LeetCodeHot100.Problem candidate = hot100.random();
            if (!used.contains(candidate.slug())) return candidate;
        }
        return LeetCodeHot100.PROBLEMS.stream()
                .filter(problem -> !used.contains(problem.slug()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("题库已用尽，无法再出编程题"));
    }

    private GeneratedQuestion parse(JsonNode root, QuestionTarget target) {
        String text = root.path("questionText").asText("").strip();
        if (text.isBlank()) throw new IllegalStateException("未返回题目正文");
        if (text.length() > MAX_QUESTION_LENGTH) throw new IllegalStateException("题目正文过长");
        AdaptiveRubric rubric = AdaptiveRubric.create(target.knowledgePoint(), target.difficulty(),
                strings(root.path("expectedPoints")), strings(root.path("depthMarkers")),
                strings(root.path("commonMisconceptions")));
        return new GeneratedQuestion(text, target.questionType(), rubric.expectedPoints(),
                target.withRubric(rubric), null);
    }

    private static List<String> strings(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item.asText("").strip();
            if (!value.isBlank()) values.add(value);
        }
        return values;
    }
}