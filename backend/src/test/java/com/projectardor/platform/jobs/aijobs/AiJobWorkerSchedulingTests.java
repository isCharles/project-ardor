package com.projectardor.platform.jobs.aijobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.projectardor.assistant.execution.service.AgentMetrics;
import com.projectardor.platform.model.llm.service.LlmCallException;

class AiJobWorkerSchedulingTests {
    private final AiJobStore store = mock(AiJobStore.class);
    private final AiJobService service = mock(AiJobService.class);
    private final AgentMetrics metrics = mock(AgentMetrics.class);
    private final AiJobWorker worker = new AiJobWorker(store, service, metrics);

    private AiJobStore.Job job(int attempts, int maxAttempts) {
        return new AiJobStore.Job(UUID.randomUUID(), UUID.randomUUID(), AiJobType.LEARNING_EVALUATE,
                UUID.randomUUID(), "hash", "{}", "RUNNING", attempts, maxAttempts, 0,
                null, null, null, Instant.now(), Instant.now(), null, null);
    }

    @Test
    void retryableFailureWithinMaxAttemptsRequeuesWithBackoff() {
        var j = job(1, 5);
        var failure = new LlmCallException("RATE_LIMITED", "too many requests", true, null);
        worker.handleFailure(j, failure);
        verify(store).requeue(eq(j), any(Instant.class));
        verify(metrics).recordJobRetry("LEARNING_EVALUATE");
        verify(store, never()).deadLetter(any(), any(), any());
        verify(store, never()).fail(any(), any(), any());
    }

    @Test
    void retryableFailureExhaustingMaxAttemptsGoesToDeadLetter() {
        var j = job(5, 5);
        var failure = new LlmCallException("RATE_LIMITED", "too many requests", true, null);
        worker.handleFailure(j, failure);
        verify(store).deadLetter(eq(j), eq("AI_JOB_EXHAUSTED"), any());
        verify(metrics).recordJobDeadLetter("LEARNING_EVALUATE");
        verify(store, never()).requeue(any(), any());
        verify(store, never()).fail(any(), any(), any());
    }

    @Test
    void nonRetryableFailureGoesDirectlyToTerminalFail() {
        var j = job(1, 5);
        var failure = new IllegalArgumentException("bad input");
        worker.handleFailure(j, failure);
        verify(store).fail(eq(j), eq("AI_EVALUATION_FAILED"), any());
        verify(metrics).recordJobCompletion("LEARNING_EVALUATE", "FAILED");
        verify(store, never()).requeue(any(), any());
        verify(store, never()).deadLetter(any(), any(), any());
    }

    @Test
    void nonRetryableLlmExceptionUsesItsOwnCode() {
        var j = job(2, 5);
        var failure = new LlmCallException("INVALID_API_KEY", "bad key", false, null);
        worker.handleFailure(j, failure);
        verify(store).fail(eq(j), eq("INVALID_API_KEY"), any());
        verify(metrics).recordJobCompletion("LEARNING_EVALUATE", "FAILED");
    }

    @Test
    void connectionExceptionIsRetryable() {
        var j = job(1, 3);
        var failure = new RuntimeException("connection refused", new java.net.ConnectException());
        worker.handleFailure(j, failure);
        verify(store).requeue(eq(j), any(Instant.class));
        verify(metrics).recordJobRetry("LEARNING_EVALUATE");
    }

    @Test
    void backoffCapsAtTenMinutes() {
        var j = job(10, 20);
        var failure = new LlmCallException("TIMEOUT", "timed out", true, null);
        worker.handleFailure(j, failure);
        verify(store).requeue(eq(j), org.mockito.ArgumentMatchers.argThat(
                (Instant retryAfter) -> retryAfter.isBefore(Instant.now().plusSeconds(601))));
    }
}
