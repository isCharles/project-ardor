package com.projectardor.jobsearch.discovery.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.projectardor.platform.jobs.aijobs.AiJobStore;
import com.projectardor.platform.notification.BackgroundTaskStore;

class JobDiscoveryRetryHandlerTests {

    private final JobDiscoveryService service = mock(JobDiscoveryService.class);
    private final AiJobStore jobs = mock(AiJobStore.class);
    private final JobDiscoveryRetryHandler handler = new JobDiscoveryRetryHandler(service, jobs);
    private final UUID owner = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();

    private BackgroundTaskStore.Task task() {
        var task = mock(BackgroundTaskStore.Task.class);
        when(task.id()).thenReturn(jobId);
        when(task.resourceId()).thenReturn(runId);
        return task;
    }

    @Test void onlyOwnsTheDiscoveryKind() {
        assertThat(handler.kinds()).containsExactly("JOB_DISCOVERY");
    }

    @Test void aTerminalRunCreatesANewRunInsteadOfReplayingTheFinishedJob() {
        var run = mock(JobDiscoveryService.RunView.class);
        when(run.status()).thenReturn("FAILED");
        when(service.run(owner, runId)).thenReturn(run);

        handler.retry(owner, task());

        verify(service).retry(owner, runId);
        verifyNoInteractions(jobs);
    }

    @Test void anUnfinishedRunIsReenqueuedWithoutCreatingADuplicate() {
        var run = mock(JobDiscoveryService.RunView.class);
        when(run.status()).thenReturn("RUNNING");
        when(service.run(owner, runId)).thenReturn(run);

        handler.retry(owner, task());

        verify(jobs).retry(owner, jobId);
        verify(service, never()).retry(any(), any());
    }
}
