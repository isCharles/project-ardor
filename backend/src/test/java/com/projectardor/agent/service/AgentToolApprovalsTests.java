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
    void executorCannotReadOrWriteBeforeAnExactSingleUseApproval() throws Exception {
        gate.begin(owner, run);
        Probe probe = new Probe();
        var tools = ApprovedAgentTools.wrap(probe, owner, run, gate);
        var executor = tools.values().iterator().next();
        var call = ToolExecutionRequest.builder().id("call-one").name("read_private_data").arguments("{}").build();
        var result = CompletableFuture.supplyAsync(() -> executor.execute(call, null));
        var permission = pending(gate);
        assertThat(probe.calls).hasValue(0);
        assertThat(permission.tool()).isEqualTo("read_private_data");
        assertThat(permission.arguments()).isEqualTo("{}");
        assertThat(gate.list(UUID.randomUUID(), run)).isEmpty();
        assertThatThrownBy(() -> gate.decide(UUID.randomUUID(), run, permission.id(), true))
                .isInstanceOf(com.projectardor.common.web.ResourceNotFoundException.class);
        gate.decide(owner, run, permission.id(), true);
        assertThat(result.get(2, TimeUnit.SECONDS)).contains("result");
        assertThat(probe.calls).hasValue(1);
        assertThatThrownBy(() -> gate.decide(owner, run, permission.id(), true)).isInstanceOf(IllegalStateException.class);
        assertThat(gate.list(owner, run).getFirst().status()).isEqualTo("CONSUMED");
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
    void legacyChatWithoutAnInteractiveRunCannotExecuteBusinessTools() {
        Probe probe = new Probe();
        var executor = ApprovedAgentTools.wrap(probe, owner, null, gate).values().iterator().next();
        assertThat(executor.execute(ToolExecutionRequest.builder().name("read_private_data").arguments("{}").build(), null))
                .contains("PERMISSION_NOT_GRANTED");
        assertThat(probe.calls).hasValue(0);
    }

    private AgentToolApprovals.View pending(AgentToolApprovals approvals) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (approvals.list(owner, run).isEmpty() && System.nanoTime() < deadline) Thread.sleep(1);
        assertThat(approvals.list(owner, run)).isNotEmpty();
        return approvals.list(owner, run).getFirst();
    }
    public static class Probe {
        final AtomicInteger calls = new AtomicInteger();
        @Tool(name = "read_private_data", value = "Read owner data")
        public String read() { calls.incrementAndGet(); return "result"; }
    }
}
