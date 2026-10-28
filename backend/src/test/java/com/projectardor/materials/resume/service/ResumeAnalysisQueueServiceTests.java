package com.projectardor.materials.resume.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.projectardor.materials.resume.domain.Resume;
import com.projectardor.materials.resume.domain.ResumeAnalysisJob;
import com.projectardor.materials.resume.repository.ResumeAnalysisJobRepository;
import com.projectardor.materials.resume.repository.ResumeAnalysisRepository;
import com.projectardor.materials.resume.repository.ResumeRepository;

class ResumeAnalysisQueueServiceTests {

    @Test
    void requestLocksTheUserAndResumeBeforeLookingForAnActiveJob() {
        ResumeRepository resumes = mock(ResumeRepository.class);
        ResumeAnalysisRepository analyses = mock(ResumeAnalysisRepository.class);
        ResumeAnalysisJobRepository jobs = mock(ResumeAnalysisJobRepository.class);
        ResumeAnalysisQueueService service = new ResumeAnalysisQueueService(resumes, analyses, jobs, new tools.jackson.databind.ObjectMapper());
        UUID userId = UUID.randomUUID();
        UUID resumeId = UUID.randomUUID();
        Resume resume = Resume.create(resumeId, userId, "resume.pdf", "application/pdf", "key", 1, "hash");
        resume.markParsed("parsed");
        when(resumes.findByIdAndUserId(resumeId, userId)).thenReturn(Optional.of(resume));
        when(analyses.findByResumeIdAndUserId(resumeId, userId)).thenReturn(Optional.empty());
        when(jobs.findFirstByUserIdAndResumeIdAndEvaluationKeyAndStatusInOrderByCreatedAtDesc(
                org.mockito.ArgumentMatchers.eq(userId), org.mockito.ArgumentMatchers.eq(resumeId),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyCollection())).thenReturn(Optional.empty());
        when(jobs.saveAndFlush(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> call.getArgument(0));

        service.request(userId, resumeId);

        verify(jobs).lockRequestSlot(userId, resumeId);
    }

    @Test
    void aDifferentJobDescriptionQueuesItsOwnJobInsteadOfRefusingTheResume() {
        ResumeRepository resumes = mock(ResumeRepository.class);
        ResumeAnalysisRepository analyses = mock(ResumeAnalysisRepository.class);
        ResumeAnalysisJobRepository jobs = mock(ResumeAnalysisJobRepository.class);
        var service = new ResumeAnalysisQueueService(resumes, analyses, jobs, new tools.jackson.databind.ObjectMapper());
        UUID userId = UUID.randomUUID(), resumeId = UUID.randomUUID();
        Resume resume = Resume.create(resumeId, userId, "resume.pdf", "application/pdf", "key", 1, "hash");
        resume.markParsed("两段实习，一个 Java 后端项目");
        when(resumes.findByIdAndUserId(resumeId, userId)).thenReturn(Optional.of(resume));
        when(analyses.findByResumeIdAndUserIdAndEvaluationKey(org.mockito.ArgumentMatchers.eq(resumeId),
                org.mockito.ArgumentMatchers.eq(userId), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.empty());
        when(jobs.findFirstByUserIdAndResumeIdAndEvaluationKeyAndStatusInOrderByCreatedAtDesc(
                org.mockito.ArgumentMatchers.eq(userId), org.mockito.ArgumentMatchers.eq(resumeId),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(Optional.empty());
        when(jobs.saveAndFlush(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> call.getArgument(0));

        var java = service.request(userId, resumeId, "Java 后端", "要求 Spring Boot 与 MySQL", "");
        var data = service.request(userId, resumeId, "数据分析", "要求 SQL 与指标建模", "");

        assertThat(java.jobId()).isNotEqualTo(data.jobId());
        var captor = org.mockito.ArgumentCaptor.forClass(ResumeAnalysisJob.class);
        verify(jobs, org.mockito.Mockito.times(2)).saveAndFlush(captor.capture());
        assertThat(captor.getAllValues()).extracting(ResumeAnalysisJob::getEvaluationKey)
                .doesNotContainNull().doesNotHaveDuplicates();
    }

    @Test
    void theSameJobDescriptionReusesTheInFlightJob() {
        ResumeRepository resumes = mock(ResumeRepository.class);
        ResumeAnalysisRepository analyses = mock(ResumeAnalysisRepository.class);
        ResumeAnalysisJobRepository jobs = mock(ResumeAnalysisJobRepository.class);
        var service = new ResumeAnalysisQueueService(resumes, analyses, jobs, new tools.jackson.databind.ObjectMapper());
        UUID userId = UUID.randomUUID(), resumeId = UUID.randomUUID();
        Resume resume = Resume.create(resumeId, userId, "resume.pdf", "application/pdf", "key", 1, "hash");
        resume.markParsed("parsed");
        when(resumes.findByIdAndUserId(resumeId, userId)).thenReturn(Optional.of(resume));
        var running = ResumeAnalysisJob.create(userId, resumeId, ResumeScoringContext.freeze("Java 后端", "要求 Spring Boot"), "key-1");
        when(jobs.findFirstByUserIdAndResumeIdAndEvaluationKeyAndStatusInOrderByCreatedAtDesc(
                org.mockito.ArgumentMatchers.eq(userId), org.mockito.ArgumentMatchers.eq(resumeId),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(Optional.of(running));

        var again = service.request(userId, resumeId, "Java 后端", "要求 Spring Boot", "");

        assertThat(again.jobId()).isEqualTo(running.getId());
        verify(jobs, org.mockito.Mockito.never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void progressFollowsTheJobThatThisRequestStarted() {
        ResumeRepository resumes = mock(ResumeRepository.class);
        ResumeAnalysisJobRepository jobs = mock(ResumeAnalysisJobRepository.class);
        var service = new ResumeAnalysisQueueService(resumes, mock(ResumeAnalysisRepository.class), jobs,
                new tools.jackson.databind.ObjectMapper());
        UUID userId = UUID.randomUUID(), resumeId = UUID.randomUUID();
        var resume = Resume.create(resumeId, userId, "resume.pdf", "application/pdf", "key", 1, "hash");
        when(resumes.findByIdAndUserId(resumeId, userId)).thenReturn(Optional.of(resume));
        var job = ResumeAnalysisJob.create(userId, resumeId, ResumeScoringContext.freeze("", ""), "key-1");
        when(jobs.findByIdAndUserId(job.getId(), userId)).thenReturn(Optional.of(job));

        assertThat(service.status(userId, resumeId, job.getId()).jobId()).isEqualTo(job.getId());
        verify(jobs, org.mockito.Mockito.never()).findFirstByUserIdAndResumeIdOrderByCreatedAtDesc(userId, resumeId);
    }

    @Test
    void staleRecoveryFailsExhaustedJobsAndRequeuesOnlyRetryableOnes() {
        ResumeAnalysisJobRepository jobs = mock(ResumeAnalysisJobRepository.class);
        when(jobs.failExhaustedStale(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(3)))
                .thenReturn(2);
        when(jobs.recoverStale(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(3)))
                .thenReturn(4);
        ResumeAnalysisQueueService service = new ResumeAnalysisQueueService(
                mock(ResumeRepository.class), mock(ResumeAnalysisRepository.class), jobs, new tools.jackson.databind.ObjectMapper());

        assertThat(service.recoverStaleJobs()).isEqualTo(6);
    }
}
