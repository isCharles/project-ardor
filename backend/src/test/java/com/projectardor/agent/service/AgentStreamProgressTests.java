package com.projectardor.agent.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import com.projectardor.common.web.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentStreamProgressTests {
    private final MutableClock clock = new MutableClock();
    private final AgentStreamProgress progress = new AgentStreamProgress(clock);
    private final UUID owner = UUID.randomUUID();
    private final UUID request = UUID.randomUUID();

    @Test
    void silenceIsNotPresentedAsThinkingAndFirstOutputUsesStartTime() {
        progress.begin(owner, request);
        clock.advance(31);
        assertThat(progress.get(owner, request).phase()).isEqualTo("WAITING_DATA");
        assertThat(progress.get(owner, request).thinkingOutput()).isEmpty();
        progress.thinking(request, "Public reasoning");
        clock.advance(424);
        progress.answer(request, "Answer");
        var view = progress.get(owner, request);
        assertThat(view.firstThinkingMs()).isEqualTo(31_000);
        assertThat(view.firstOutputMs()).isEqualTo(455_000);
        assertThat(view.answerPartial()).isEqualTo("Answer");
        assertThat(view.thinkingOutput()).isEqualTo("Public reasoning");
    }

    @Test
    void progressDoesNotRevealAnotherUsersRequest() {
        progress.begin(owner, request);
        assertThatThrownBy(() -> progress.get(UUID.randomUUID(), request))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage("执行进度不存在");
        assertThatThrownBy(() -> progress.get(owner, UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage("执行进度不存在");
    }

    @Test
    void completedAndFailedRunsCannotBeReopenedByLateCallbacks() {
        for (boolean failed : new boolean[] {false, true}) {
            UUID id = UUID.randomUUID();
            progress.begin(owner, id);
            progress.finish(id, failed);
            progress.tool(id, "Late tool");
            progress.waiting(id);
            progress.thinking(id, "Late reasoning");
            progress.answer(id, "Late answer");
            progress.finish(id, !failed);
            var view = progress.get(owner, id);
            assertThat(view.phase()).isEqualTo(failed ? "FAILED" : "COMPLETED");
            assertThat(view.answerPartial()).isEmpty();
            assertThat(view.thinkingOutput()).isEmpty();
        }
    }

    @Test
    void thinkingAndAnswerHaveIndependentBoundedBuffers() {
        progress.begin(owner, request);
        progress.thinking(request, "x".repeat(32_001));
        progress.answer(request, "y".repeat(32_001));
        var view = progress.get(owner, request);
        assertThat(view.thinkingOutput()).hasSize(32_000);
        assertThat(view.answerPartial()).hasSize(32_000);
        assertThat(view.truncated()).isTrue();
    }

    @Test
    void toolTimeIsNotMisclassifiedAsUpstreamSilenceAndEntriesExpire() {
        progress.begin(owner, request);
        progress.tool(request, "Reading plans");
        clock.advance(35);
        assertThat(progress.get(owner, request).phase()).isEqualTo("TOOL");
        progress.waiting(request);
        assertThat(progress.get(owner, request).silentSeconds()).isZero();
        clock.advance(7201);
        progress.expire();
        assertThatThrownBy(() -> progress.get(owner, request)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void failedPublicOutputAndStepsSurviveAProcessReplacementWithoutRestoringPermissions() {
        AgentProgressStore store = org.mockito.Mockito.mock(AgentProgressStore.class);
        AgentStreamProgress firstProcess = new AgentStreamProgress(clock, store);
        firstProcess.begin(owner, request);
        firstProcess.thinking(request, "Provider public reasoning");
        firstProcess.answer(request, "Partial answer");
        firstProcess.step(request, "Read plans", 4, "COMPLETED");
        firstProcess.finish(request, true);
        var snapshot = org.mockito.ArgumentCaptor.forClass(AgentStreamProgress.Snapshot.class);
        org.mockito.Mockito.verify(store).save(org.mockito.ArgumentMatchers.eq(owner), org.mockito.ArgumentMatchers.eq(request), snapshot.capture());
        org.mockito.Mockito.when(store.find(owner, request)).thenReturn(java.util.Optional.of(new AgentProgressStore.Saved(snapshot.getValue(), "INTERRUPTED")));
        AgentStreamProgress restarted = new AgentStreamProgress(clock, store);
        var recovered = restarted.get(owner, request);
        assertThat(recovered.phase()).isEqualTo("INTERRUPTED");
        assertThat(recovered.answerPartial()).isEqualTo("Partial answer");
        assertThat(recovered.thinkingOutput()).isEqualTo("Provider public reasoning");
        assertThat(recovered.steps()).extracting(AgentStreamProgress.Step::label).containsExactly("Read plans");
        UUID stranger = UUID.randomUUID();
        org.mockito.Mockito.when(store.find(stranger, request)).thenReturn(java.util.Optional.empty());
        assertThatThrownBy(() -> restarted.get(stranger, request)).isInstanceOf(ResourceNotFoundException.class);
        restarted.answer(request, "Must not resume a persisted run");
        assertThat(restarted.get(owner, request).answerPartial()).isEqualTo("Partial answer");
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-14T00:00:00Z");
        void advance(long seconds) { now = now.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
