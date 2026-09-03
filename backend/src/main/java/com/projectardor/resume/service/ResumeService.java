package com.projectardor.resume.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.projectardor.common.json.LlmJsonParser;
import com.projectardor.common.web.ResourceNotFoundException;
import com.projectardor.llm.service.LlmGateway;
import com.projectardor.resume.domain.Resume;
import com.projectardor.resume.domain.ResumeAnalysis;
import com.projectardor.resume.domain.ResumeParseStatus;
import com.projectardor.resume.repository.ResumeAnalysisRepository;
import com.projectardor.resume.repository.ResumeRepository;
import com.projectardor.resume.storage.ResumeStorage;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class ResumeService {

    private static final long MAX_SIZE_BYTES = 10 * 1024 * 1024;
    private static final int MAX_LLM_TEXT_CHARS = 50_000;
    private static final List<String> ANALYSIS_ARRAY_KEYS = List.of(
            "education", "experience", "projects", "skills",
            "strengths", "weaknesses", "possibleTargetRoles", "recommendations");

    private final ResumeRepository resumeRepository;
    private final ResumeAnalysisRepository analysisRepository;
    private final ResumeStorage storage;
    private final ResumeTextExtractor textExtractor;
    private final LlmGateway llmGateway;
    private final LlmJsonParser jsonParser;
    private final ObjectMapper objectMapper;

    public ResumeService(
            ResumeRepository resumeRepository,
            ResumeAnalysisRepository analysisRepository,
            ResumeStorage storage,
            ResumeTextExtractor textExtractor,
            LlmGateway llmGateway,
            LlmJsonParser jsonParser,
            ObjectMapper objectMapper) {
        this.resumeRepository = resumeRepository;
        this.analysisRepository = analysisRepository;
        this.storage = storage;
        this.textExtractor = textExtractor;
        this.llmGateway = llmGateway;
        this.jsonParser = jsonParser;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Resume upload(UUID userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择 PDF 或 DOCX 简历");
        }
        if (file.getSize() > MAX_SIZE_BYTES) {
            throw new IllegalArgumentException("简历文件不能超过 10MB");
        }

        String filename = safeFilename(file.getOriginalFilename());
        String extension = extension(filename);
        byte[] content;
        try {
            content = file.getBytes();
        } catch (Exception exception) {
            throw new IllegalArgumentException("无法读取上传文件", exception);
        }
        validateSignature(content, extension);

        UUID resumeId = UUID.randomUUID();
        String storageKey = storage.save(content, extension);
        try {
            Resume resume = resumeRepository.saveAndFlush(Resume.create(
                    resumeId,
                    userId,
                    filename,
                    canonicalContentType(extension),
                    storageKey,
                    content.length,
                    sha256(content)));
            try {
                resume.markParsed(textExtractor.extract(content, extension));
            } catch (IllegalArgumentException exception) {
                resume.markFailed();
            }
            return resumeRepository.saveAndFlush(resume);
        } catch (RuntimeException exception) {
            storage.delete(storageKey);
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public List<Resume> list(UUID userId) {
        return resumeRepository.findAllByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public Resume get(UUID userId, UUID resumeId) {
        return resumeRepository.findByIdAndUserId(resumeId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("简历不存在"));
    }

    @Transactional(readOnly = true)
    public ResumeAnalysis getAnalysis(UUID userId, UUID resumeId) {
        get(userId, resumeId);
        return analysisRepository.findByResumeIdAndUserId(resumeId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("该简历尚未分析"));
    }

    @Transactional(readOnly = true)
    public ResumeAnalysis getAnalysisById(UUID userId, UUID analysisId) {
        return analysisRepository.findByIdAndUserId(analysisId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("简历分析不存在"));
    }

    public ResumeAnalysis analyze(UUID userId, UUID resumeId) {
        Resume resume = get(userId, resumeId);
        var existing = analysisRepository.findByResumeIdAndUserId(resumeId, userId);
        if (existing.isPresent()) {
            return existing.get();
        }
        if (resume.getParseStatus() != ResumeParseStatus.PARSED || resume.getParsedText() == null) {
            throw new IllegalStateException("简历文本解析未成功，无法进行 AI 分析");
        }

        String text = resume.getParsedText();
        if (text.length() > MAX_LLM_TEXT_CHARS) {
            text = text.substring(0, MAX_LLM_TEXT_CHARS);
        }
        LlmGateway.LlmResult result = llmGateway.completeJson(
                userId,
                """
                你是严谨的中文求职简历分析器。只输出一个 JSON 对象，不要 Markdown，不要补充解释。
                必须包含：
                - overallScore：0 到 100 的整数综合评分；
                - categoryScores：对象，包含 content、impact、clarity、roleFit 四个 0 到 100 的整数；
                - summary：两到三句中文总结，说明整体水平、最强证据和首要问题；
                - education、experience、projects、skills、strengths、weaknesses、possibleTargetRoles、recommendations：数组。
                education、experience、projects 使用对象数组，每项尽量包含 title、evidence、assessment，明确引用简历中的事实并给出判断。
                strengths、weaknesses 每项需要说明依据，不能只写抽象标签。
                recommendations 每项必须具体可执行，说明改什么、怎么改以及预期改善；优先给出可直接用于改写简历的建议。
                评分需要严格、可解释，报告内容应详细但不重复。不得编造简历中没有的事实。
                """,
                "请分析以下简历文本：\n\n" + text);
        Map<String, Object> analysis = normalizedAnalysis(jsonParser.parseObject(result.content()));
        try {
            return analysisRepository.saveAndFlush(
                    ResumeAnalysis.create(userId, resumeId, analysis, result.model()));
        } catch (DataIntegrityViolationException exception) {
            return analysisRepository.findByResumeIdAndUserId(resumeId, userId)
                    .orElseThrow(() -> exception);
        }
    }

    @Transactional(readOnly = true)
    public List<ResumeAnalysis> listAnalyses(UUID userId) {
        return analysisRepository.findAllByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional
    public void delete(UUID userId, UUID resumeId) {
        Resume resume = get(userId, resumeId);
        resumeRepository.delete(resume);
        resumeRepository.flush();
        storage.delete(resume.getStorageKey());
    }

    private Map<String, Object> normalizedAnalysis(JsonNode node) {
        Map<String, Object> parsed = objectMapper.convertValue(node, new TypeReference<>() {});
        Map<String, Object> normalized = new LinkedHashMap<>();
        Object overallScore = parsed.get("overallScore");
        normalized.put("overallScore", overallScore instanceof Number number
                ? Math.max(0, Math.min(100, number.intValue())) : 0);
        Object categoryScores = parsed.get("categoryScores");
        Map<String, Object> normalizedScores = new LinkedHashMap<>();
        Map<?, ?> rawScores = categoryScores instanceof Map<?, ?> map ? map : Map.of();
        for (String key : List.of("content", "impact", "clarity", "roleFit")) {
            Object value = rawScores.get(key);
            normalizedScores.put(key, value instanceof Number number
                    ? Math.max(0, Math.min(100, number.intValue())) : 0);
        }
        normalized.put("categoryScores", normalizedScores);
        Object summary = parsed.get("summary");
        normalized.put("summary", summary instanceof String text ? text.strip() : "");
        for (String key : ANALYSIS_ARRAY_KEYS) {
            Object value = parsed.get(key);
            normalized.put(key, value instanceof List<?> ? value : List.of());
        }
        return normalized;
    }

    private String safeFilename(String rawFilename) {
        if (rawFilename == null || rawFilename.isBlank()) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        String normalized = rawFilename.replace('\\', '/');
        String filename = normalized.substring(normalized.lastIndexOf('/') + 1).strip();
        if (filename.isBlank() || filename.length() > 255 || filename.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("文件名不合法或过长");
        }
        return filename;
    }

    private String extension(String filename) {
        String lower = filename.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".pdf")) return ".pdf";
        if (lower.endsWith(".docx")) return ".docx";
        throw new IllegalArgumentException("只支持 PDF 或 DOCX 简历");
    }

    private void validateSignature(byte[] content, String extension) {
        boolean valid = switch (extension) {
            case ".pdf" -> content.length >= 4
                    && content[0] == '%' && content[1] == 'P' && content[2] == 'D' && content[3] == 'F';
            case ".docx" -> content.length >= 4 && content[0] == 'P' && content[1] == 'K';
            default -> false;
        };
        if (!valid) {
            throw new IllegalArgumentException("文件内容与扩展名不匹配");
        }
    }

    private String canonicalContentType(String extension) {
        return ".pdf".equals(extension)
                ? "application/pdf"
                : "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 Java 运行时不支持 SHA-256", exception);
        }
    }
}
