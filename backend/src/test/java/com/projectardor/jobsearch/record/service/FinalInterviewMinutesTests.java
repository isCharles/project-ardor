package com.projectardor.jobsearch.record.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.projectardor.common.web.ResourceNotFoundException;
import com.projectardor.jobsearch.interview.service.InterviewContextAccess;
import com.projectardor.jobsearch.record.domain.InterviewRecapJob;
import com.projectardor.jobsearch.record.repository.InterviewRecapJobRepository;
import com.projectardor.jobsearch.record.repository.InterviewRecapRepository;

class FinalInterviewMinutesTests {
    private static final String TEXT = "  Final user minutes: the interviewer asked about recovery. The candidate compared snapshot and log trade-offs.  ";

    @Test void savesExactFinalMinutesWithAnIndependentFingerprintAndOwnedStatus() {
        var jobs = mock(InterviewRecapJobRepository.class);
        var contexts = mock(InterviewContextAccess.class);
        var service = new InterviewRecapQueueService(mock(InterviewRecapRepository.class), jobs, contexts);
        UUID owner = UUID.randomUUID(), process = UUID.randomUUID(), round = UUID.randomUUID();
        Map<UUID, InterviewRecapJob> saved = new HashMap<>();
        when(jobs.saveAndFlush(any())).thenAnswer(call -> {
            InterviewRecapJob job = call.getArgument(0); saved.put(job.getId(), job); return job;
        });
        when(jobs.findByIdAndUserId(any(), any())).thenAnswer(call -> {
            var job = saved.get(call.getArgument(0));
            return job != null && job.getUserId().equals(call.getArgument(1)) ? Optional.of(job) : Optional.empty();
        });
        var finalMinutes = service.requestFinalMinutes(owner, TEXT, process, round);
        var legacy = service.request(owner, TEXT, process, round);
        var view = service.finalMinutes(owner, finalMinutes.jobId());
        assertThat(view.minutes()).isEqualTo(TEXT);
        assertThat(finalMinutes.status()).isEqualTo("QUEUED");
        assertThat(view.interviewRoundId()).isEqualTo(round);
        assertThat(saved.get(finalMinutes.jobId()).isFinalMinutes()).isTrue();
        assertThat(saved.get(finalMinutes.jobId()).getInputHash()).isNotEqualTo(saved.get(legacy.jobId()).getInputHash());
        assertThatThrownBy(() -> service.finalMinutes(UUID.randomUUID(), finalMinutes.jobId())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.finalMinutes(owner, legacy.jobId())).isInstanceOf(ResourceNotFoundException.class);
        verify(contexts, times(2)).require(owner, process, round);
    }

    @Test void finalWorkerNormalizesDirectlyWithoutDraftOrConfirmation() {
        var queue = mock(InterviewRecapQueueService.class);
        var records = mock(InterviewRecapService.class);
        var drafts = mock(InterviewMinutesService.class);
        UUID owner = UUID.randomUUID(), process = UUID.randomUUID(), round = UUID.randomUUID(), reportId = UUID.randomUUID();
        var job = InterviewRecapJob.create(owner, "hash", TEXT, process, round);
        job.useFinalMinutes();
        when(queue.claimNext()).thenReturn(Optional.of(job));
        var report = mock(InterviewRecordView.class);
        when(report.id()).thenReturn(reportId);
        when(records.normalizeFinalMinutes(owner, TEXT, process, round, job.getId())).thenReturn(report);
        new InterviewRecapWorker(queue, records, drafts).poll();
        verify(queue).complete(job.getId(), reportId);
        verifyNoInteractions(drafts);
    }

    @Test void failedNormalizationKeepsTheStoredMinutesForRetry() {
        var queue = mock(InterviewRecapQueueService.class);
        var records = mock(InterviewRecapService.class);
        var drafts = mock(InterviewMinutesService.class);
        var job = InterviewRecapJob.create(UUID.randomUUID(), "hash", TEXT, UUID.randomUUID(), null);
        job.useFinalMinutes();
        when(queue.claimNext()).thenReturn(Optional.of(job));
        var failure = new IllegalStateException("synthetic provider failure");
        when(records.normalizeFinalMinutes(any(), anyString(), any(), isNull(), any())).thenThrow(failure);
        new InterviewRecapWorker(queue, records, drafts).poll();
        verify(queue).fail(job.getId(), failure);
        assertThat(job.getRawContent()).isEqualTo(TEXT);
        verifyNoInteractions(drafts);
    }
}
