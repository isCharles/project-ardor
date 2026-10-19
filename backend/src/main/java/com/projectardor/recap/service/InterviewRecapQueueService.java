package com.projectardor.recap.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.projectardor.common.web.ResourceNotFoundException;
import com.projectardor.llm.service.LlmCallException;
import com.projectardor.opportunity.service.InterviewContextAccess;
import com.projectardor.recap.domain.InterviewRecapJob;
import com.projectardor.recap.domain.InterviewRecapJobStatus;
import com.projectardor.recap.repository.InterviewRecapJobRepository;
import com.projectardor.recap.repository.InterviewRecapRepository;

@Service
public class InterviewRecapQueueService {
    private static final Collection<InterviewRecapJobStatus> REUSABLE = List.of(
            InterviewRecapJobStatus.QUEUED, InterviewRecapJobStatus.RUNNING, InterviewRecapJobStatus.COMPLETED);
    private static final int MAX_ATTEMPTS = 5;
    private final InterviewRecapRepository recapRepository;
    private final InterviewRecapJobRepository jobRepository;
    private final InterviewContextAccess interviewContexts;

    public InterviewRecapQueueService(InterviewRecapRepository recapRepository,
            InterviewRecapJobRepository jobRepository, InterviewContextAccess interviewContexts) {
        this.recapRepository = recapRepository; this.jobRepository = jobRepository;
        this.interviewContexts = interviewContexts;
    }

    @Transactional
    public TaskResult request(UUID userId, String rawContent, UUID jobOpportunityId) {
        return request(userId, rawContent, jobOpportunityId, null);
    }

    @Transactional
    public TaskResult request(UUID userId, String rawContent, UUID jobOpportunityId, UUID roundId) {
        interviewContexts.require(userId, jobOpportunityId, roundId);
        String content = requiredContent(rawContent);
        String inputHash = inputHash(content);
        jobRepository.lockRequestSlot(userId, inputHash);
        Optional<InterviewRecapJob> active = jobRepository
                .findFirstByUserIdAndInputHashAndStatusInOrderByCreatedAtDesc(userId, inputHash, REUSABLE);
        if (active.isPresent() && (!jobOpportunityId.equals(active.get().getJobOpportunityId())
                || !java.util.Objects.equals(roundId, active.get().getInterviewRoundId()))) {
            throw new IllegalArgumentException("相同转录已关联其他面试或轮次，不会静默改挂");
        }
        return active.map(TaskResult::from)
                .orElseGet(() -> TaskResult.from(jobRepository.saveAndFlush(
                        InterviewRecapJob.create(userId, inputHash, content, jobOpportunityId, roundId))));
    }

    @Transactional
    public TaskResult fromReport(UUID userId, UUID recapId) {
        return fromReport(userId,recapId,null);
    }

    @Transactional
    public TaskResult fromReport(UUID userId, UUID recapId, UUID selectedOpportunity) {
        var recap=recapRepository.findByIdAndUserId(recapId,userId)
                .orElseThrow(() -> new ResourceNotFoundException("面经不存在"));
        if (recap.getJobOpportunityId()!=null && selectedOpportunity!=null && !recap.getJobOpportunityId().equals(selectedOpportunity))
            throw new IllegalArgumentException("不能将已有面经改挂到另一场面试");
        return request(userId,recap.getRawContent(),recap.getJobOpportunityId()==null ? selectedOpportunity : recap.getJobOpportunityId(),recap.getInterviewRoundId());
    }

    @Transactional(readOnly = true)
    public List<TaskResult> list(UUID userId) {
        return jobRepository.findTop20ByUserIdOrderByCreatedAtDesc(userId).stream().map(TaskResult::from).toList();
    }

    /**
     * Re-queues a failed job at the user's request. The raw material is still on the
     * job row, so this replays the original submission without asking the user to paste
     * it again — which matters because the common causes (LLM not configured, a Base URL
     * that could not resolve) are fixed in settings, not in the content.
     */
    @Transactional
    public TaskResult retryFailed(UUID userId, UUID jobId) {
        InterviewRecapJob job = ownedJob(userId, jobId);
        job.requeue();
        return TaskResult.from(jobRepository.save(job));
    }

    /** Drops a failed job so it stops occupying the user's task list. */
    @Transactional
    public void dismissFailed(UUID userId, UUID jobId) {
        InterviewRecapJob job = ownedJob(userId, jobId);
        if (job.getStatus() != InterviewRecapJobStatus.FAILED) {
            throw new IllegalStateException("只有失败的整理任务可以忽略");
        }
        jobRepository.delete(job);
    }

    private InterviewRecapJob ownedJob(UUID userId, UUID jobId) {
        return jobRepository.findByIdAndUserId(jobId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("整理任务不存在"));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<InterviewRecapJob> claimNext() { return jobRepository.claimNext(); }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(UUID jobId, UUID recapId) {
        InterviewRecapJob job = jobRepository.findById(jobId).orElseThrow(); job.complete(recapId); jobRepository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(UUID jobId, RuntimeException failure) {
        InterviewRecapJob job = jobRepository.findById(jobId).orElseThrow();
        String code = failure instanceof LlmCallException llm ? llm.getCode() : "RECAP_FAILED";
        boolean retryable = failure instanceof LlmCallException llm && llm.isRetryable();
        String message = failure.getMessage() == null ? "面经整理失败" : failure.getMessage();
        if (retryable && job.getAttempts() < MAX_ATTEMPTS) {
            job.retry(code, message, Instant.now().plus(Duration.ofSeconds(15L * job.getAttempts())));
        } else job.fail(code, message);
        jobRepository.save(job);
    }

    @Transactional
    public int recoverStaleJobs() {
        Instant staleBefore = Instant.now().minus(Duration.ofMinutes(30));
        int failed = jobRepository.failExhaustedStale(staleBefore, MAX_ATTEMPTS);
        return failed + jobRepository.recoverStale(staleBefore, MAX_ATTEMPTS);
    }

    public static String requiredContent(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("面试内容不能为空");
        String content = value.strip();
        if (content.length() < 30) throw new IllegalArgumentException("面试内容太短，请至少提供一段问题或回忆");
        if (value.length() > 50_000) throw new IllegalArgumentException("面试内容不能超过 50000 个字符");
        return value;
    }

    private static String inputHash(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(("MINUTES_V1\n" + content).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) { throw new IllegalStateException("无法生成内容指纹", exception); }
    }

    public record TaskResult(UUID jobId, UUID recapId, InterviewRecapJobStatus status, int attempts,
            String errorCode, String errorMessage, Instant createdAt, Instant finishedAt) {
        static TaskResult from(InterviewRecapJob job) {
            return new TaskResult(job.getId(), job.getRecapId(), job.getStatus(), job.getAttempts(),
                    job.getErrorCode(), job.getErrorMessage(), job.getCreatedAt(), job.getFinishedAt());
        }
        static TaskResult completed(UUID recapId, Instant createdAt) {
            return new TaskResult(null, recapId, InterviewRecapJobStatus.COMPLETED, 0, null, null, createdAt, null);
        }
    }
}
