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

import com.projectardor.common.json.LlmJsonParser;
import com.projectardor.common.web.ResourceNotFoundException;
import com.projectardor.interview.domain.InterviewAnswer;
import com.projectardor.interview.domain.InterviewEvaluation;
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

    public InterviewService(
            InterviewSessionRepository sessionRepository,
            InterviewQuestionRepository questionRepository,
            InterviewAnswerRepository answerRepository,
            InterviewEvaluationRepository evaluationRepository,
            ProfileService profileService,
            ResumeService resumeService,
            LlmGateway llmGateway,
            LlmJsonParser jsonParser,
            ObjectMapper objectMapper) {
        this.sessionRepository = sessionRepository;
        this.questionRepository = questionRepository;
        this.answerRepository = answerRepository;
        this.evaluationRepository = evaluationRepository;
        this.profileService = profileService;
        this.resumeService = resumeService;
        this.llmGateway = llmGateway;
        this.jsonParser = jsonParser;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public InterviewSession create(
            UUID userId,
            UUID resumeAnalysisId,
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
                格式必须为 {"questions":[{"questionText":"...","questionType":"TECHNICAL|PROJECT|BEHAVIORAL","evaluationCriteria":["..."]}]}。
                问题应结合候选人背景、简历分析、目标公司和岗位，循序渐进，不得编造候选人经历。
                """,
                "请生成模拟面试题：\n" + context);
        List<QuestionDraft> drafts = parseQuestions(jsonParser.parseObject(result.content()), questionCount);

        InterviewSession session = sessionRepository.save(InterviewSession.create(
                userId,
                resumeAnalysisId,
                normalizedCompany,
                normalizedRole));
        List<InterviewQuestion> questions = new ArrayList<>();
        for (int index = 0; index < drafts.size(); index++) {
            QuestionDraft draft = drafts.get(index);
            questions.add(InterviewQuestion.create(
                    userId, session.getId(), index + 1,
                    draft.questionText(), draft.questionType(), draft.evaluationCriteria()));
        }
        questionRepository.saveAll(questions);
        return session;
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

    @Transactional
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
        InterviewEvaluation saved = evaluationRepository.save(
                InterviewEvaluation.create(userId, sessionId, score, evaluation, result.model()));
        session.complete();
        sessionRepository.save(session);
        return saved;
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
            String type = node.path("questionType").asText("TECHNICAL").strip();
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
