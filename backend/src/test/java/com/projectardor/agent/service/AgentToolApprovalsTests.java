package com.projectardor.agent.service;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AgentToolApprovalsTests {
    private final UUID owner = UUID.randomUUID();
    private final UUID run = UUID.randomUUID();
    private final AgentToolApprovals gate = new AgentToolApprovals(Duration.ofSeconds(5));

    @Test
    void ordinaryToolsExecuteWithoutApprovalAndHaveZeroApprovalWait() throws Exception {
        gate.begin(owner, run);
        var nanos = new java.util.concurrent.atomic.AtomicLong();
        var timings = new AgentToolTimings(nanos::get);
        var probe = new TimedProbe(nanos);
        var executor = ApprovedAgentTools.wrap(probe, owner, run, gate, null, timings).values().iterator().next();
        var call = ToolExecutionRequest.builder().id("timed-call").name("get_profile").arguments("{}").build();
        assertThat(executor.execute(call, null)).contains("result");
        assertThat(gate.list(owner, run)).isEmpty();
        var measured = timings.remove(call.id());
        assertThat(measured.approvalWaitMs()).isZero();
        assertThat(measured.businessMs()).isEqualTo(3);
        assertThat(measured.executed()).isTrue();
    }

    @Test
    void closedRunReportsZeroBusinessExecution() throws Exception {
        gate.begin(owner, run);
        var timings = new AgentToolTimings();
        var probe = new Probe();
        var executor = ApprovedAgentTools.wrap(probe, owner, run, gate, null, timings).values().iterator().next();
        var call = ToolExecutionRequest.builder().id("denied-call").name("get_profile").arguments("{}").build();
        gate.close(owner, run);
        assertThat(executor.execute(call, null)).contains("PERMISSION_NOT_GRANTED");
        var denied = timings.remove(call.id());
        assertThat(denied.businessMs()).isZero();
        assertThat(denied.executed()).isFalse();
        assertThat(probe.calls).hasValue(0);
    }

    public static class TimedProbe {
        private final java.util.concurrent.atomic.AtomicLong nanos;
        TimedProbe(java.util.concurrent.atomic.AtomicLong nanos) { this.nanos = nanos; }
        @Tool(name = "get_profile", value = "Read fixture")
        public String read() { nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(3)); return "result"; }
    }

    @Test
    void ordinaryReadUsesActiveOwnerRunWithoutCreatingPermissionRequests() throws Exception {
        gate.begin(owner, run);
        Probe probe = new Probe();
        var tools = ApprovedAgentTools.wrap(probe, owner, run, gate);
        var executor = tools.values().iterator().next();
        var call = ToolExecutionRequest.builder().id("call-one").name("get_profile").arguments("{}").build();
        assertThat(executor.execute(call, null)).contains("result");
        assertThat(gate.list(owner, run)).isEmpty();
        assertThat(probe.calls).hasValue(1);
        var foreign = ApprovedAgentTools.wrap(probe, UUID.randomUUID(), run, gate).values().iterator().next();
        assertThat(foreign.execute(call, null)).contains("PERMISSION_NOT_GRANTED");
        gate.close(owner, run);
        assertThat(executor.execute(call, null)).contains("PERMISSION_NOT_GRANTED");
        assertThat(probe.calls).hasValue(1);
    }

    @Test
    void denialStopsRepeatedRequestsWithinTheRun() throws Exception {
        gate.begin(owner, run);
        var result = CompletableFuture.supplyAsync(() -> gate.await(owner, run, "write", "{\"id\":\"one\"}"));
        var permission = pending(gate);
        gate.decide(owner, run, permission.id(), false);
        assertThat(result.get(2, TimeUnit.SECONDS)).isFalse();
        assertThat(gate.await(owner, run, "different_write", "{}")).isFalse();
        assertThat(gate.list(owner, run)).hasSize(1);
    }

    @Test
    void expiryCloseAndMissingRuntimeNeverGrantExecution() throws Exception {
        var shortGate = new AgentToolApprovals(Duration.ofMillis(20));
        shortGate.begin(owner, run);
        assertThat(shortGate.await(owner, run, "write", "{}")).isFalse();
        var expired = shortGate.list(owner, run).getFirst();
        assertThat(expired.status()).isEqualTo("EXPIRED");
        assertThatThrownBy(() -> shortGate.decide(owner, run, expired.id(), true)).isInstanceOf(IllegalStateException.class);
        assertThat(new AgentToolApprovals().await(owner, run, "write", "{}")).isFalse();
        gate.begin(owner, run);
        var pending = CompletableFuture.supplyAsync(() -> gate.await(owner, run, "read", "{}"));
        pending(gate);
        gate.close(owner, run);
        assertThat(pending.get(2, TimeUnit.SECONDS)).isFalse();
        assertThat(gate.await(owner, run, "read", "{}")).isFalse();
    }

    @Test
    void synchronousChatCanUseOrdinaryBusinessToolsWithoutAnApprovalChannel() {
        Probe probe = new Probe();
        var executor = ApprovedAgentTools.wrap(probe, owner, null, gate).values().iterator().next();
        assertThat(executor.execute(ToolExecutionRequest.builder().name("get_profile").arguments("{}").build(), null))
                .contains("result");
        assertThat(probe.calls).hasValue(1);
    }

    @Test
    void denialAndCloseAlsoBlockProposalMetadataReads() throws Exception {
        gate.begin(owner, run);
        ProposalProbe probe = new ProposalProbe();
        var executor = ApprovedAgentTools.wrap(probe, owner, run, gate).values().iterator().next();
        var call = ToolExecutionRequest.builder().name("delete_memory").arguments("{}").build();
        assertThat(executor.execute(call, null)).contains("proposal");
        assertThat(probe.calls).hasValue(1);
        var denied = CompletableFuture.supplyAsync(() -> gate.await(owner, run, "read", "{}"));
        gate.decide(owner, run, pending(gate).id(), false);
        assertThat(denied.get(2, TimeUnit.SECONDS)).isFalse();
        assertThat(executor.execute(call, null)).contains("PERMISSION_NOT_GRANTED");
        gate.close(owner, run);
        assertThat(executor.execute(call, null)).contains("PERMISSION_NOT_GRANTED");
        assertThat(probe.calls).hasValue(1);
    }

    private AgentToolApprovals.View pending(AgentToolApprovals approvals) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (approvals.list(owner, run).isEmpty() && System.nanoTime() < deadline) Thread.sleep(1);
        assertThat(approvals.list(owner, run)).isNotEmpty();
        return approvals.list(owner, run).getFirst();
    }
    public static class Probe {
        final AtomicInteger calls = new AtomicInteger();
        @Tool(name = "get_profile", value = "Read owner data")
        public String read() { calls.incrementAndGet(); return "result"; }
    }
    public static class ProposalProbe {
        final AtomicInteger calls = new AtomicInteger();
        @Tool(name = "delete_memory", value = "Prepare confirmation metadata")
        public String propose() { calls.incrementAndGet(); return "proposal"; }
    }

    @Test void unregisteredDestructiveToolCannotBypassTheProposalBoundary() {
        gate.begin(owner, run);
        var probe = new UnsafeDeletionProbe();
        var executor = ApprovedAgentTools.wrap(probe, owner, run, gate).values().iterator().next();
        assertThat(executor.execute(ToolExecutionRequest.builder().name("delete_unregistered").arguments("{}").build(), null))
                .contains("PERMISSION_NOT_GRANTED");
        assertThat(probe.calls).hasValue(0);
    }
    public static class UnsafeDeletionProbe {
        final AtomicInteger calls = new AtomicInteger();
        @Tool(name = "delete_unregistered", value = "Unsafe direct deletion fixture")
        public String delete() { calls.incrementAndGet(); return "deleted"; }
    }

    @Test void trustedWriteScopeAndDurableRunAreBothRequired() {
        var probe = new WriteProbe();
        var call = ToolExecutionRequest.builder().name("create_calendar_task").arguments("{}").build();
        assertThat(ApprovedAgentTools.wrap(probe, owner, null, gate).values().iterator().next().execute(call, null)).contains("PERMISSION_NOT_GRANTED");
        gate.begin(owner, run);
        probe.request = "Search the web for calendar examples";
        var executor = ApprovedAgentTools.wrap(probe, owner, run, gate).values().iterator().next();
        assertThat(executor.execute(call, null)).contains("PERMISSION_NOT_GRANTED");
        probe.request = "Please create a calendar task for tomorrow";
        assertThat(executor.execute(call, null)).contains("created");
        assertThat(gate.list(owner, run)).isEmpty();
        gate.close(owner, run);
        assertThat(executor.execute(call, null)).contains("PERMISSION_NOT_GRANTED");
        assertThat(probe.calls).hasValue(1);
    }
    public static class WriteProbe implements ToolIntentScope {
        String request;
        final AtomicInteger calls = new AtomicInteger();
        public boolean permitsWrite(String tool) { return ToolIntentPolicy.permits(tool, request); }
        @Tool(name = "create_calendar_task", value = "Write fixture")
        public String write() { calls.incrementAndGet(); return "created"; }
    }
}
