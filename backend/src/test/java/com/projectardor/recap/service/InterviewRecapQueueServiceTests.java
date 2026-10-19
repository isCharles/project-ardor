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
import com.projectardor.opportunity.repository.OpportunityRoundRepository;
import com.projectardor.opportunity.service.InterviewContextAccess;
import com.projectardor.recap.repository.InterviewRecapJobRepository;
import com.projectardor.recap.repository.InterviewRecapRepository;

class InterviewRecapQueueServiceTests {

    private static final String CONTENT = "这是一段足够长的面试回忆内容，用于验证并发请求会先锁定同一份输入再创建任务。";

    @Test void oldReportCanStartNewDraftWithoutChangingTheReport() {
        var recaps = mock(InterviewRecapRepository.class);
        var jobs = mock(InterviewRecapJobRepository.class);
        var opportunities = mock(JobOpportunityRepository.class);
        var service = new InterviewRecapQueueService(recaps, jobs, contexts(opportunities));
        UUID owner = UUID.randomUUID(), process = UUID.randomUUID(), another = UUID.randomUUID();
        var recap = com.projectardor.recap.domain.InterviewRecap.create(owner, "Fixture",
                com.projectardor.recap.domain.InterviewRecapSource.NOTES, CONTENT, "hash", "Overview",
                java.util.List.of(), java.util.List.of(), "fixture", process);
        when(opportunities.findByIdAndUserId(eq(process), eq(owner)))
                .thenReturn(Optional.of(JobOpportunity.create(owner, "Example", null, null, null)));
        when(opportunities.findByIdAndUserId(eq(another), eq(owner)))
                .thenReturn(Optional.of(JobOpportunity.create(owner, "Another", null, null, null)));
        when(recaps.findByIdAndUserId(recap.getId(),owner)).thenReturn(Optional.of(recap));
        when(jobs.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        var result=service.fromReport(owner,recap.getId());
        assertThat(result.jobId()).isNotNull();
        assertThat(result.recapId()).isNull();
        assertThat(recap.getJobOpportunityId()).isEqualTo(process);
        org.mockito.Mockito.verify(recaps,org.mockito.Mockito.never()).saveAndFlush(any());
        assertThatThrownBy(() -> service.fromReport(UUID.randomUUID(),recap.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void requestLocksTheUserAndContentFingerprintBeforeCreatingAJob() {
        InterviewRecapRepository recaps = mock(InterviewRecapRepository.class);
        InterviewRecapJobRepository jobs = mock(InterviewRecapJobRepository.class);
        JobOpportunityRepository opportunities = mock(JobOpportunityRepository.class);
        InterviewRecapQueueService service = new InterviewRecapQueueService(recaps, jobs, contexts(opportunities));
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

    @Test void unlinkedLegacyReportRequiresAnOwnedSelectionWithoutChangingItsOriginalLink() {
        var recaps=mock(InterviewRecapRepository.class);var jobs=mock(InterviewRecapJobRepository.class);
        var opportunities=mock(JobOpportunityRepository.class);var service=new InterviewRecapQueueService(recaps,jobs,contexts(opportunities));
        UUID user=UUID.randomUUID(),selected=UUID.randomUUID();
        var recap=com.projectardor.recap.domain.InterviewRecap.create(user,"Legacy",com.projectardor.recap.domain.InterviewRecapSource.NOTES,
                CONTENT,"hash","Overview",java.util.List.of(),java.util.List.of(),"fixture",null);
        when(recaps.findByIdAndUserId(recap.getId(),user)).thenReturn(Optional.of(recap));
        when(opportunities.findByIdAndUserId(selected,user)).thenReturn(Optional.of(JobOpportunity.create(user,"Owned",null,null,null)));
        when(jobs.saveAndFlush(any())).thenAnswer(call->call.getArgument(0));
        assertThatThrownBy(()->service.fromReport(user,recap.getId())).isInstanceOf(IllegalArgumentException.class);
        assertThat(service.fromReport(user,recap.getId(),selected).jobId()).isNotNull();
        assertThat(recap.getJobOpportunityId()).isNull();
        assertThatThrownBy(()->service.fromReport(user,recap.getId(),UUID.randomUUID())).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void requestRequiresAnInterviewTheUserOwns() {
        InterviewRecapQueueService service = new InterviewRecapQueueService(
                mock(InterviewRecapRepository.class), mock(InterviewRecapJobRepository.class),
                contexts(mock(JobOpportunityRepository.class)));
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
                mock(InterviewRecapRepository.class), jobs, contexts(mock(JobOpportunityRepository.class)));

        assertThat(service.recoverStaleJobs()).isEqualTo(4);
    }

    private static InterviewContextAccess contexts(JobOpportunityRepository opportunities) {
        return new InterviewContextAccess(opportunities, mock(OpportunityRoundRepository.class));
    }

    @Test void sameRoundRetryReusesJobWithoutChangingSourceOrCreatingAnotherJob() {
        var jobs = mock(InterviewRecapJobRepository.class);
        var contexts = mock(InterviewContextAccess.class);
        var service = new InterviewRecapQueueService(mock(InterviewRecapRepository.class), jobs, contexts);
        UUID owner = UUID.randomUUID(), process = UUID.randomUUID(), round = UUID.randomUUID();
        var existing = com.projectardor.recap.domain.InterviewRecapJob.create(owner, "fixture", CONTENT, process, round);
        when(jobs.findFirstByUserIdAndInputHashAndStatusInOrderByCreatedAtDesc(eq(owner), any(), any()))
                .thenReturn(Optional.of(existing));

        assertThat(service.request(owner, CONTENT, process, round).jobId()).isEqualTo(existing.getId());
        verify(contexts).require(owner, process, round);
        verify(jobs, org.mockito.Mockito.never()).saveAndFlush(any());
        assertThat(existing.getRawContent()).isEqualTo(CONTENT);
        assertThat(existing.getInterviewRoundId()).isEqualTo(round);
    }

    @Test void duplicateSourceCannotSilentlyMoveToAnotherRoundOrDropItsRound() {
        var jobs = mock(InterviewRecapJobRepository.class);
        var service = new InterviewRecapQueueService(mock(InterviewRecapRepository.class), jobs, mock(InterviewContextAccess.class));
        UUID owner = UUID.randomUUID(), process = UUID.randomUUID(), round = UUID.randomUUID();
        var existing = com.projectardor.recap.domain.InterviewRecapJob.create(owner, "fixture", CONTENT, process, round);
        when(jobs.findFirstByUserIdAndInputHashAndStatusInOrderByCreatedAtDesc(eq(owner), any(), any()))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.request(owner, CONTENT, process, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不会静默改挂");
        assertThatThrownBy(() -> service.request(owner, CONTENT, process))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不会静默改挂");
        verify(jobs, org.mockito.Mockito.never()).saveAndFlush(any());
        assertThat(existing.getInterviewRoundId()).isEqualTo(round);
    }
}
