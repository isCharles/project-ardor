package com.projectardor.recap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.projectardor.common.web.ResourceNotFoundException;
import com.projectardor.opportunity.domain.JobOpportunity;
import com.projectardor.opportunity.repository.JobOpportunityRepository;
import com.projectardor.recap.repository.InterviewRecapJobRepository;
import com.projectardor.recap.repository.InterviewRecapRepository;

class InterviewRecapQueueServiceTests {

    private static final String CONTENT = "这是一段足够长的面试回忆内容，用于验证并发请求会先锁定同一份输入再创建任务。";

    @Test void cachedRecapAttachesToOwnedProcessAndRejectsAnotherProcess() {
        var recaps = mock(InterviewRecapRepository.class);
        var jobs = mock(InterviewRecapJobRepository.class);
        var opportunities = mock(JobOpportunityRepository.class);
        var service = new InterviewRecapQueueService(recaps, jobs, opportunities);
        UUID owner = UUID.randomUUID(), process = UUID.randomUUID(), another = UUID.randomUUID();
        var recap = com.projectardor.recap.domain.InterviewRecap.create(owner, "Fixture",
                com.projectardor.recap.domain.InterviewRecapSource.NOTES, CONTENT, "hash", "Overview",
                java.util.List.of(), java.util.List.of(), "fixture", null);
        when(opportunities.findByIdAndUserId(eq(process), eq(owner)))
                .thenReturn(Optional.of(JobOpportunity.create(owner, "Example", null, null, null)));
        when(opportunities.findByIdAndUserId(eq(another), eq(owner)))
                .thenReturn(Optional.of(JobOpportunity.create(owner, "Another", null, null, null)));
        when(recaps.findByUserIdAndInputHash(eq(owner), any())).thenReturn(Optional.of(recap));
        service.request(owner, CONTENT, process);
        assertThat(recap.getJobOpportunityId()).isEqualTo(process);
        verify(recaps).saveAndFlush(recap);
        assertThatThrownBy(() -> service.request(owner, CONTENT, another))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("另一场面试");
    }

    @Test
    void requestLocksTheUserAndContentFingerprintBeforeCreatingAJob() {
        InterviewRecapRepository recaps = mock(InterviewRecapRepository.class);
        InterviewRecapJobRepository jobs = mock(InterviewRecapJobRepository.class);
        JobOpportunityRepository opportunities = mock(JobOpportunityRepository.class);
        InterviewRecapQueueService service = new InterviewRecapQueueService(recaps, jobs, opportunities);
        UUID userId = UUID.randomUUID();
        UUID opportunityId = UUID.randomUUID();
        when(opportunities.findByIdAndUserId(opportunityId, userId))
                .thenReturn(Optional.of(JobOpportunity.create(userId, "字节跳动", "后端", null, null)));
        when(recaps.findByUserIdAndInputHash(eq(userId), any())).thenReturn(Optional.empty());
        when(jobs.findFirstByUserIdAndInputHashAndStatusInOrderByCreatedAtDesc(
                eq(userId), any(), any())).thenReturn(Optional.empty());
        when(jobs.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

        service.request(userId, CONTENT, opportunityId);

        verify(jobs).lockRequestSlot(eq(userId), any());
    }

    @Test
    void requestRequiresAnInterviewTheUserOwns() {
        InterviewRecapQueueService service = new InterviewRecapQueueService(
                mock(InterviewRecapRepository.class), mock(InterviewRecapJobRepository.class),
                mock(JobOpportunityRepository.class));
        UUID userId = UUID.randomUUID();

        assertThatThrownBy(() -> service.request(userId, CONTENT, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("请选择所属面试");
        assertThatThrownBy(() -> service.request(userId, CONTENT, UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("面试流程不存在");
    }

    @Test
    void staleRecoveryFailsExhaustedJobsAndRequeuesOnlyRetryableOnes() {
        InterviewRecapJobRepository jobs = mock(InterviewRecapJobRepository.class);
        when(jobs.failExhaustedStale(any(), eq(5))).thenReturn(1);
        when(jobs.recoverStale(any(), eq(5))).thenReturn(3);
        InterviewRecapQueueService service = new InterviewRecapQueueService(
                mock(InterviewRecapRepository.class), jobs, mock(JobOpportunityRepository.class));

        assertThat(service.recoverStaleJobs()).isEqualTo(4);
    }
}
