package com.projectardor.resume.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.projectardor.resume.domain.Resume;
import com.projectardor.resume.repository.ResumeAnalysisJobRepository;
import com.projectardor.resume.repository.ResumeAnalysisRepository;
import com.projectardor.resume.repository.ResumeRepository;

class ResumeAnalysisQueueServiceTests {

    @Test
    void requestLocksTheUserAndResumeBeforeLookingForAnActiveJob() {
        ResumeRepository resumes = mock(ResumeRepository.class);
        ResumeAnalysisRepository analyses = mock(ResumeAnalysisRepository.class);
        ResumeAnalysisJobRepository jobs = mock(ResumeAnalysisJobRepository.class);
        ResumeAnalysisQueueService service = new ResumeAnalysisQueueService(resumes, analyses, jobs);
        UUID userId = UUID.randomUUID();
        UUID resumeId = UUID.randomUUID();
        Resume resume = Resume.create(resumeId, userId, "resume.pdf", "application/pdf", "key", 1, "hash");
        resume.markParsed("parsed");
        when(resumes.findByIdAndUserId(resumeId, userId)).thenReturn(Optional.of(resume));
        when(analyses.findByResumeIdAndUserId(resumeId, userId)).thenReturn(Optional.empty());
        when(jobs.findFirstByUserIdAndResumeIdAndStatusInOrderByCreatedAtDesc(
                org.mockito.ArgumentMatchers.eq(userId), org.mockito.ArgumentMatchers.eq(resumeId),
                org.mockito.ArgumentMatchers.anyCollection())).thenReturn(Optional.empty());
        when(jobs.saveAndFlush(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> call.getArgument(0));

        service.request(userId, resumeId);

        verify(jobs).lockRequestSlot(userId, resumeId);
    }

    @Test
    void staleRecoveryFailsExhaustedJobsAndRequeuesOnlyRetryableOnes() {
        ResumeAnalysisJobRepository jobs = mock(ResumeAnalysisJobRepository.class);
        when(jobs.failExhaustedStale(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(3)))
                .thenReturn(2);
        when(jobs.recoverStale(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(3)))
                .thenReturn(4);
        ResumeAnalysisQueueService service = new ResumeAnalysisQueueService(
                mock(ResumeRepository.class), mock(ResumeAnalysisRepository.class), jobs);

        assertThat(service.recoverStaleJobs()).isEqualTo(6);
    }
}
