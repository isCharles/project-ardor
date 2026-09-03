package com.projectardor.recap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.projectardor.recap.repository.InterviewRecapJobRepository;
import com.projectardor.recap.repository.InterviewRecapRepository;

class InterviewRecapQueueServiceTests {

    @Test
    void requestLocksTheUserAndContentFingerprintBeforeCreatingAJob() {
        InterviewRecapRepository recaps = mock(InterviewRecapRepository.class);
        InterviewRecapJobRepository jobs = mock(InterviewRecapJobRepository.class);
        InterviewRecapQueueService service = new InterviewRecapQueueService(recaps, jobs);
        UUID userId = UUID.randomUUID();
        when(recaps.findByUserIdAndInputHash(eq(userId), any())).thenReturn(Optional.empty());
        when(jobs.findFirstByUserIdAndInputHashAndStatusInOrderByCreatedAtDesc(
                eq(userId), any(), any())).thenReturn(Optional.empty());
        when(jobs.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

        service.request(userId, "这是一段足够长的面试回忆内容，用于验证并发请求会先锁定同一份输入再创建任务。");

        verify(jobs).lockRequestSlot(eq(userId), any());
    }

    @Test
    void staleRecoveryFailsExhaustedJobsAndRequeuesOnlyRetryableOnes() {
        InterviewRecapJobRepository jobs = mock(InterviewRecapJobRepository.class);
        when(jobs.failExhaustedStale(any(), eq(5))).thenReturn(1);
        when(jobs.recoverStale(any(), eq(5))).thenReturn(3);
        InterviewRecapQueueService service = new InterviewRecapQueueService(
                mock(InterviewRecapRepository.class), jobs);

        assertThat(service.recoverStaleJobs()).isEqualTo(4);
    }
}
