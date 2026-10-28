package com.projectardor.materials.resume.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.projectardor.common.web.ResourceNotFoundException;
import com.projectardor.platform.model.llm.service.LlmCallException;
import com.projectardor.materials.resume.domain.Resume;
import com.projectardor.materials.resume.domain.ResumeAnalysis;
import com.projectardor.materials.resume.domain.ResumeAnalysisJob;
import com.projectardor.materials.resume.domain.ResumeAnalysisJobStatus;
import com.projectardor.materials.resume.domain.ResumeParseStatus;
import com.projectardor.materials.resume.repository.ResumeAnalysisJobRepository;
import com.projectardor.materials.resume.repository.ResumeAnalysisRepository;
import com.projectardor.materials.resume.repository.ResumeRepository;

@Service
public class ResumeAnalysisQueueService {

    private static final List<ResumeAnalysisJobStatus> ACTIVE_STATUSES = List.of(
            ResumeAnalysisJobStatus.QUEUED, ResumeAnalysisJobStatus.RUNNING);
    private static final int MAX_ATTEMPTS = 3;

    private final ResumeRepository resumeRepository;
    private final ResumeAnalysisRepository analysisRepository;
    private final ResumeAnalysisJobRepository jobRepository;
    private final tools.jackson.databind.ObjectMapper mapper;

    public ResumeAnalysisQueueService(
            ResumeRepository resumeRepository,
            ResumeAnalysisRepository analysisRepository,
            ResumeAnalysisJobRepository jobRepository,
            tools.jackson.databind.ObjectMapper mapper) {
        this.resumeRepository = resumeRepository;
        this.analysisRepository = analysisRepository;
        this.jobRepository = jobRepository;
        this.mapper = mapper;
    }

    @Transactional
    public TaskResult request(UUID userId, UUID resumeId) {
        return request(userId, resumeId, "", "", ResumeScoringFamilies.DEFAULT_ID);
    }

    @Transactional
    public TaskResult request(UUID userId, UUID resumeId, String role, String jobDescription) {
        return request(userId, resumeId, role, jobDescription, ResumeScoringFamilies.DEFAULT_ID);
    }

    @Transactional
    public TaskResult request(UUID userId, UUID resumeId, String role, String jobDescription, String roleFamily) {
        Resume resume = ownedResume(userId, resumeId);
        if (resume.getParseStatus() != ResumeParseStatus.PARSED || resume.getParsedText() == null) {
            throw new IllegalStateException("简历文本解析未成功，无法进行 AI 分析");
        }
        jobRepository.lockRequestSlot(userId, resumeId);
        var context = ResumeScoringContext.freeze(role, jobDescription, roleFamily);
        String key = ResumeScoringContext.key(resume.getParsedText(), context, mapper);
        Optional<ResumeAnalysis> analysis = analysisRepository.findByResumeIdAndUserIdAndEvaluationKey(resumeId, userId, key);
        Optional<ResumeAnalysisJob> active = jobRepository
                .findFirstByUserIdAndResumeIdAndEvaluationKeyAndStatusInOrderByCreatedAtDesc(
                        userId, resumeId, key, ACTIVE_STATUSES);
        if (active.isPresent()) return TaskResult.from(active.get());
        Optional<ResumeAnalysisJob> legacy = jobRepository
                .findFirstByUserIdAndResumeIdAndEvaluationKeyIsNullAndStatusInOrderByCreatedAtDesc(userId, resumeId, ACTIVE_STATUSES);
        // A job already in flight when V85 ran has no key, and the new index excludes keyless rows,
        // so this same evaluation would otherwise be queued — and billed — a second time.
        if (legacy.isPresent() && legacy.get().getScoringContext().equals(context)) {
            legacy.get().adoptEvaluationKey(key);
            return TaskResult.from(jobRepository.saveAndFlush(legacy.get()));
        }
        if (analysis.isPresent()) {
            var previous = jobRepository.findFirstByUserIdAndResumeIdAndEvaluationKeyOrderByCreatedAtDesc(userId, resumeId, key);
            if (previous.isPresent() && previous.get().getStatus() == ResumeAnalysisJobStatus.COMPLETED
                    && analysis.get().getId().equals(previous.get().getAnalysisId())) return TaskResult.from(previous.get());
            var completed = ResumeAnalysisJob.create(userId, resumeId, context, key);
            completed.complete(analysis.get().getId());
            return TaskResult.from(jobRepository.saveAndFlush(completed));
        }
        return TaskResult.from(jobRepository.saveAndFlush(ResumeAnalysisJob.create(userId, resumeId, context, key)));
    }

    @Transactional(readOnly = true)
    public TaskResult status(UUID userId, UUID resumeId) {
        return status(userId, resumeId, null);
    }

    /**
     * A resume can now hold one in-flight job per job description, so progress is read per job when
     * the caller knows which request it made; without a job id it falls back to the newest request.
     */
    @Transactional(readOnly = true)
    public TaskResult status(UUID userId, UUID resumeId, UUID jobId) {
        ownedResume(userId, resumeId);
        if (jobId != null) return TaskResult.from(jobRepository.findByIdAndUserId(jobId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("该简历尚未提交分析任务")));
        var latest = jobRepository.findFirstByUserIdAndResumeIdOrderByCreatedAtDesc(userId, resumeId);
        if (latest.isPresent()) return TaskResult.from(latest.get());
        Optional<ResumeAnalysis> analysis = analysisRepository.findByResumeIdAndUserId(resumeId, userId);
        if (analysis.isPresent()) return TaskResult.completed(resumeId, analysis.get().getId());
        throw new ResourceNotFoundException("该简历尚未提交分析任务");
    }

    @Transactional(readOnly = true)
    public Optional<TaskResult> statusIfPresent(UUID userId, UUID resumeId) {
        var latest = jobRepository.findFirstByUserIdAndResumeIdOrderByCreatedAtDesc(userId, resumeId);
        if (latest.isPresent()) return Optional.of(TaskResult.from(latest.get()));
        Optional<ResumeAnalysis> analysis = analysisRepository.findByResumeIdAndUserId(resumeId, userId);
        if (analysis.isPresent()) return Optional.of(TaskResult.completed(resumeId, analysis.get().getId()));
        return jobRepository.findFirstByUserIdAndResumeIdOrderByCreatedAtDesc(userId, resumeId)
                .map(TaskResult::from);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ResumeAnalysisJob> claimNext() {
        return jobRepository.claimNext();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(UUID jobId, UUID analysisId) {
        ResumeAnalysisJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("简历分析任务不存在"));
        job.complete(analysisId);
        jobRepository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(UUID jobId, RuntimeException failure) {
        ResumeAnalysisJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("简历分析任务不存在"));
        String code = failure instanceof LlmCallException llmFailure
                ? llmFailure.getCode()
                : "ANALYSIS_FAILED";
        boolean retryable = failure instanceof LlmCallException llmFailure && llmFailure.isRetryable();
        String message = failure.getMessage() == null ? "简历分析失败" : failure.getMessage();
        if (retryable && job.getAttempts() < MAX_ATTEMPTS) {
            job.retry(code, message, Instant.now().plus(Duration.ofSeconds(10L * job.getAttempts())));
        } else {
            job.fail(code, message);
        }
        jobRepository.save(job);
    }

    @Transactional
    public int recoverStaleJobs() {
        Instant staleBefore = Instant.now().minus(Duration.ofMinutes(10));
        int failed = jobRepository.failExhaustedStale(staleBefore, MAX_ATTEMPTS);
        return failed + jobRepository.recoverStale(staleBefore, MAX_ATTEMPTS);
    }

    private Resume ownedResume(UUID userId, UUID resumeId) {
        return resumeRepository.findByIdAndUserId(resumeId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("简历不存在"));
    }

    public record TaskResult(
            UUID jobId,
            UUID resumeId,
            ResumeAnalysisJobStatus status,
            UUID analysisId,
            int attempts,
            String errorCode,
            String errorMessage) {

        static TaskResult from(ResumeAnalysisJob job) {
            return new TaskResult(
                    job.getId(), job.getResumeId(), job.getStatus(), job.getAnalysisId(),
                    job.getAttempts(), job.getErrorCode(), job.getErrorMessage());
        }

        static TaskResult completed(UUID resumeId, UUID analysisId) {
            return new TaskResult(
                    null, resumeId, ResumeAnalysisJobStatus.COMPLETED,
                    analysisId, 0, null, null);
        }
    }
}
