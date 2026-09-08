package com.projectardor.interview.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.projectardor.common.json.LlmJsonParser;
import com.projectardor.common.web.ResourceNotFoundException;
import com.projectardor.interview.domain.InterviewAnswer;
import com.projectardor.interview.domain.InterviewEvaluation;
import com.projectardor.interview.domain.InterviewModality;
import com.projectardor.interview.domain.InterviewQuestion;
import com.projectardor.interview.domain.InterviewSession;
import com.projectardor.interview.domain.InterviewStatus;
import com.projectardor.interview.repository.InterviewAnswerRepository;
import com.projectardor.interview.repository.InterviewEvaluationRepository;
import com.projectardor.interview.repository.InterviewQuestionRepository;
import com.projectardor.interview.repository.InterviewSessionRepository;
import com.projectardor.llm.service.LlmGateway;
import com.projectardor.profile.domain.UserProfile;
import com.projectardor.profile.service.ProfileService;
import com.projectardor.resume.domain.ResumeAnalysis;
import com.projectardor.resume.service.ResumeService;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class InterviewService {

    private static final List<String> EVALUATION_KEYS = List.of(
            "strengths", "weaknesses", "knowledgeGaps",
            "communicationIssues", "suggestedNextSteps");

    private final InterviewSessionRepository sessionRepository;
    private final InterviewQuestionRepository questionRepository;
    private final InterviewAnswerRepository answerRepository;
    private final InterviewEvaluationRepository evaluationRepository;
    private final ProfileService profileService;
    private final ResumeService resumeService;
    private final LlmGateway llmGateway;
    private final LlmJsonParser jsonParser;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;

    public InterviewService(
            InterviewSessionRepository sessionRepository,
            InterviewQuestionRepository questionRepository,
            InterviewAnswerRepository answerRepository,
            InterviewEvaluationRepository evaluationRepository,
            ProfileService profileService,
            ResumeService resumeService,
            LlmGateway llmGateway,
            LlmJsonParser jsonParser,
            ObjectMapper objectMapper,
            org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.sessionRepository = sessionRepository;
        this.questionRepository = questionRepository;
        this.answerRepository = answerRepository;
        this.evaluationRepository = evaluationRepository;
        this.profileService = profileService;
        this.resumeService = resumeService;
        this.llmGateway = llmGateway;
        this.jsonParser = jsonParser;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public InterviewSession create(
            UUID userId,
            UUID resumeAnalysisId,
            String targetCompany,
            String targetRole,
            int questionCount) {
        return create(userId, resumeAnalysisId, InterviewModality.TEXT,
                targetCompany, targetRole, questionCount);
    }

    public InterviewSession create(
            UUID userId,
            UUID resumeAnalysisId,
            InterviewModality modality,
            String targetCompany,
            String targetRole,
            int questionCount) {
        if (questionCount < 3 || questionCount > 10) {
            throw new IllegalArgumentException("面试题数量必须在 3 到 10 之间");
        }
        String normalizedRole = normalizeRequired(targetRole, "目标岗位不能为空");
        String normalizedCompany = normalizeNullable(targetCompany);
        UserProfile profile = profileService.get(userId);
        ResumeAnalysis resumeAnalysis = resumeAnalysisId == null
                ? null
                : resumeService.getAnalysisById(userId, resumeAnalysisId);

        String context = toJson(Map.of(
                "profile", Map.of(
                        "headline", profile.getHeadline() == null ? "" : profile.getHeadline(),
                        "targetRoles", profile.getTargetRoles()),
                "resumeAnalysis", resumeAnalysis == null ? Map.of() : resumeAnalysis.getAnalysis(),
                "targetCompany", normalizedCompany == null ? "" : normalizedCompany,
                "targetRole", normalizedRole,
                "questionCount", questionCount));
        LlmGateway.LlmResult result = llmGateway.completeJson(
                userId,
                """
                你是中文技术面试官。只输出 JSON 对象，不要 Markdown。
                格式必须为 {"questions":[{"questionText":"...","questionType":"TECHNICAL|PROJECT|BEHAVIORAL|CODING","evaluationCriteria":["..."]}]}。
                问题应结合候选人背景、简历分析、目标公司和岗位，循序渐进，不得编造候选人经历。
                技术岗位且题目数不少于 3 时，至少生成一道 CODING 编程题；题目要写清输入、输出、约束和示例，允许候选人用文字说明思路并给出代码，不要求在线运行。
                """,
                "请生成模拟面试题：\n" + context);
        List<QuestionDraft> drafts = parseQuestions(jsonParser.parseObject(result.content()), questionCount);
        if (questionCount >= 3 && isTechnicalRole(normalizedRole)
                && drafts.stream().noneMatch(draft -> "CODING".equals(draft.questionType()))) {
            drafts.set(drafts.size() - 1, new QuestionDraft(
                    "请用你熟悉的语言实现 twoSum：输入整数数组 nums 和整数 target，返回两个元素下标，使它们之和等于 target。假设恰有一个答案且同一元素不能重复使用。示例：nums=[2,7,11,15]，target=9，输出 [0,1]。请说明时间与空间复杂度。",
                    "CODING", List.of("代码正确且覆盖边界情况", "能解释哈希表解法", "复杂度分析准确")));
        }

        return transactions.execute(status -> {
            InterviewSession session = sessionRepository.save(InterviewSession.create(
                    userId, resumeAnalysisId, modality, normalizedCompany, normalizedRole));
            List<InterviewQuestion> questions = new ArrayList<>();
            for (int index = 0; index < drafts.size(); index++) {
                QuestionDraft draft = drafts.get(index);
                questions.add(InterviewQuestion.create(
                        userId, session.getId(), index + 1,
                        draft.questionText(), draft.questionType(), draft.evaluationCriteria()));
            }
            questionRepository.saveAll(questions);
            return session;
        });
    }

    @Transactional(readOnly = true)
    public List<InterviewSession> list(UUID userId) {
        return sessionRepository.findAllByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public InterviewSession get(UUID userId, UUID sessionId) {
        return sessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("模拟面试不存在"));
    }

    @Transactional(readOnly = true)
    public InterviewProgress getNextQuestion(UUID userId, UUID sessionId) {
        InterviewSession session = get(userId, sessionId);
        List<InterviewQuestion> questions = questions(userId, sessionId);
        Map<UUID, InterviewAnswer> answers = answers(userId, sessionId).stream()
                .collect(Collectors.toMap(InterviewAnswer::getInterviewQuestionId, Function.identity()));
        InterviewQuestion next = questions.stream()
                .filter(question -> !answers.containsKey(question.getId()))
                .findFirst()
                .orElse(null);
        return new InterviewProgress(session, next, answers.size(), questions.size());
    }

    @Transactional(readOnly = true)
    public InterviewQuestion getQuestion(UUID userId, UUID sessionId, UUID questionId) {
        get(userId, sessionId);
        return questionRepository.findByIdAndUserIdAndInterviewSessionId(questionId, userId, sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("面试题不存在"));
    }

    @Transactional
    public InterviewProgress submitAnswer(
            UUID userId,
            UUID sessionId,
            UUID questionId,
            String answerText,
            Integer durationSeconds) {
        InterviewSession session = get(userId, sessionId);
        if (session.getStatus() != InterviewStatus.IN_PROGRESS) {
            throw new IllegalStateException("当前面试不能提交答案");
        }
        InterviewQuestion question = questionRepository
                .findByIdAndUserIdAndInterviewSessionId(questionId, userId, sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("面试题不存在"));
        InterviewProgress progress = getNextQuestion(userId, sessionId);
        if (progress.nextQuestion() == null || !progress.nextQuestion().getId().equals(question.getId())) {
            throw new IllegalStateException("请按顺序回答当前题目");
        }
        if (answerRepository.findByInterviewQuestionIdAndUserId(questionId, userId).isPresent()) {
            throw new IllegalStateException("该题已经回答");
        }
        String normalizedAnswer = normalizeRequired(answerText, "答案不能为空");
        if (durationSeconds != null && durationSeconds < 0) {
            throw new IllegalArgumentException("答题时长不能为负数");
        }
        answerRepository.save(InterviewAnswer.create(
                userId, sessionId, questionId, normalizedAnswer, durationSeconds));
        return getNextQuestion(userId, sessionId);
    }

    public InterviewEvaluation finish(UUID userId, UUID sessionId) {
        InterviewSession session = get(userId, sessionId);
        var existing = evaluationRepository.findByInterviewSessionIdAndUserId(sessionId, userId);
        if (existing.isPresent()) return existing.get();
        if (session.getStatus() != InterviewStatus.IN_PROGRESS) {
            throw new IllegalStateException("当前面试不能结束");
        }
        List<InterviewQuestion> questions = questions(userId, sessionId);
        Map<UUID, InterviewAnswer> answers = answers(userId, sessionId).stream()
                .collect(Collectors.toMap(InterviewAnswer::getInterviewQuestionId, Function.identity()));
        if (questions.isEmpty() || answers.size() != questions.size()) {
            throw new IllegalStateException("请回答全部题目后再结束面试");
        }

        List<Map<String, Object>> transcript = questions.stream()
                .map(question -> Map.<String, Object>of(
                        "sequence", question.getSequenceNumber(),
                        "question", question.getQuestionText(),
                        "type", question.getQuestionType(),
                        "criteria", question.getEvaluationCriteria(),
                        "answer", answers.get(question.getId()).getAnswerText()))
                .toList();
        LlmGateway.LlmResult result = llmGateway.completeJson(
                userId,
                """
                你是严谨的中文技术面试评价官。只输出 JSON 对象，不要 Markdown。
                必须包含 overallScore（0-100 数字）以及数组字段 strengths、weaknesses、knowledgeGaps、communicationIssues、suggestedNextSteps。
                评价只能基于给出的题目和答案，反馈必须具体、可执行。
                """,
                "目标公司：" + valueOrEmpty(session.getTargetCompany())
                        + "\n目标岗位：" + session.getTargetRole()
                        + "\n面试记录：" + toJson(transcript));
        JsonNode root = jsonParser.parseObject(result.content());
        if (!root.path("overallScore").isNumber()) {
            throw new IllegalStateException("LLM 未返回有效的面试总分");
        }
        BigDecimal score = root.path("overallScore").decimalValue().setScale(2, RoundingMode.HALF_UP);
        if (score.compareTo(BigDecimal.ZERO) < 0 || score.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalStateException("LLM 返回的面试总分不在 0 到 100 之间");
        }
        Map<String, Object> evaluation = normalizedEvaluation(root);
        return transactions.execute(status -> {
            InterviewEvaluation saved = evaluationRepository.save(
                    InterviewEvaluation.create(userId, sessionId, score, evaluation, result.model()));
            session.complete();
            sessionRepository.save(session);
            return saved;
        });
    }

    @Transactional(readOnly = true)
    public InterviewEvaluation getEvaluation(UUID userId, UUID sessionId) {
        get(userId, sessionId);
        return evaluationRepository.findByInterviewSessionIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("该面试尚未生成评价"));
    }

    @Transactional
    public InterviewSession cancel(UUID userId, UUID sessionId) {
        InterviewSession session = get(userId, sessionId);
        session.cancel();
        return sessionRepository.save(session);
    }

    @Transactional
    public void delete(UUID userId, UUID sessionId) {
        InterviewSession session = get(userId, sessionId);
        sessionRepository.delete(session);
        sessionRepository.flush();
    }

    private List<InterviewQuestion> questions(UUID userId, UUID sessionId) {
        return questionRepository.findAllByUserIdAndInterviewSessionIdOrderBySequenceNumber(userId, sessionId);
    }

    private List<InterviewAnswer> answers(UUID userId, UUID sessionId) {
        return answerRepository.findAllByUserIdAndInterviewSessionId(userId, sessionId);
    }

    private List<QuestionDraft> parseQuestions(JsonNode root, int expectedCount) {
        JsonNode questionNodes = root.path("questions");
        if (!questionNodes.isArray()) throw new IllegalStateException("LLM 未返回面试题数组");
        List<QuestionDraft> drafts = new ArrayList<>();
        for (JsonNode node : questionNodes) {
            String text = node.path("questionText").asText().strip();
            if (text.isBlank()) continue;
            String type = node.path("questionType").asText("TECHNICAL").strip().toUpperCase(java.util.Locale.ROOT);
            if (!java.util.Set.of("TECHNICAL", "PROJECT", "BEHAVIORAL", "CODING").contains(type)) type = "TECHNICAL";
            List<String> criteria = new ArrayList<>();
            if (node.path("evaluationCriteria").isArray()) {
                node.path("evaluationCriteria").forEach(item -> {
                    if (!item.asText().isBlank()) criteria.add(item.asText().strip());
                });
            }
            drafts.add(new QuestionDraft(text, type, criteria));
            if (drafts.size() == expectedCount) break;
        }
        if (drafts.size() < 3) throw new IllegalStateException("LLM 返回的有效面试题少于 3 道");
        return drafts;
    }

    private boolean isTechnicalRole(String role) {
        String normalized = role.toLowerCase(java.util.Locale.ROOT);
        return java.util.List.of("开发", "工程师", "程序", "算法", "测试", "架构", "数据", "运维", "研发",
                "java", "backend", "frontend", "fullstack", "developer", "engineer", "software", "ai", "ml")
                .stream().anyMatch(normalized::contains);
    }

    private Map<String, Object> normalizedEvaluation(JsonNode root) {
        Map<String, Object> parsed = objectMapper.convertValue(root, new TypeReference<>() {});
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (String key : EVALUATION_KEYS) {
            Object value = parsed.get(key);
            normalized.put(key, value instanceof List<?> ? value : List.of());
        }
        return normalized;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("无法构造 LLM 输入", exception);
        }
    }

    private String normalizeRequired(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        return value.strip();
    }

    private String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private String valueOrEmpty(String value) { return value == null ? "" : value; }

    private record QuestionDraft(String questionText, String questionType, List<String> evaluationCriteria) {}

    public record InterviewProgress(
            InterviewSession session,
            InterviewQuestion nextQuestion,
            int answeredCount,
            int totalQuestions) {
    }
}
