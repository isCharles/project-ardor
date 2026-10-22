package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutor;

class ApprovedAgentToolsTests {

    static class FakeTools {
        @Tool(name = "fake_write", value = "写操作")
        public String write(@P("值") String value) {
            return "executed:" + value;
        }
    }

    /** Names registered in ToolCatalog so the audit can resolve module/capability. */
    static class AuditTools {
        @Tool(name = "delete_memory", value = "提议删除记忆")
        public String deleteMemory(@P("记忆 ID") String memoryId) {
            return "{\"status\":\"CONFIRMATION_REQUIRED\"}";
        }

        @Tool(name = "list_memories", value = "读取记忆")
        public String listMemories() {
            return "[]";
        }

        @Tool(name = "update_calendar_advice", value = "保存日历建议")
        public String updateCalendarAdvice(@P("建议") String advice) {
            return "{\"status\":\"SAVED\"}";
        }
    }

    private static final UUID USER = UUID.randomUUID();
    private static final UUID RUN = UUID.randomUUID();
    private static final UUID CONVERSATION = UUID.randomUUID();

    private static ToolExecutor writeExecutor(AgentToolApprovals approvals, AgentPermissionGrants permissions) {
        var executors = ApprovedAgentTools.wrap(new FakeTools(), USER, RUN, approvals, null,
                new AgentToolTimings(), waiting -> {}, "帮我写", permissions);
        return executors.entrySet().stream()
                .filter(entry -> entry.getKey().name().equals("fake_write"))
                .findFirst().orElseThrow().getValue();
    }

    private static ToolExecutionRequest writeRequest() {
        return ToolExecutionRequest.builder().name("fake_write").arguments("{\"value\":\"hi\"}").build();
    }

    private static ToolExecutor auditedExecutor(String toolName, AgentToolApprovals approvals,
            AgentPermissionGrants permissions, AgentExecutionAuditStore auditStore, AgentStreamLease lease) {
        var executors = ApprovedAgentTools.wrap(new AuditTools(), USER, RUN, approvals, lease,
                new AgentToolTimings(), waiting -> {}, "帮我处理", permissions, auditStore, CONVERSATION);
        return executors.entrySet().stream()
                .filter(entry -> entry.getKey().name().equals(toolName))
                .findFirst().orElseThrow().getValue();
    }

    private static ToolExecutionRequest request(String name, String arguments) {
        return ToolExecutionRequest.builder().id("call-" + name).name(name).arguments(arguments).build();
    }

    @Test
    void autoApprovedWritesSkipTheDialogAndAreAudited() {
        AgentToolApprovals approvals = mock(AgentToolApprovals.class);
        when(approvals.canPropose(USER, RUN)).thenReturn(true);
        AgentPermissionGrants permissions = mock(AgentPermissionGrants.class);
        when(permissions.autoApproves(USER, "fake_write")).thenReturn(true);

        String result = writeExecutor(approvals, permissions).execute(writeRequest(), null);

        assertThat(result).isEqualTo("executed:hi");
        verify(approvals, never()).await(any(), any(), any(), any());
        verify(permissions).recordAutoApproval(eq(USER), eq(RUN), eq("fake_write"), any());
    }

    @Test
    void writesWithoutAGrantStillAskForApproval() {
        AgentToolApprovals approvals = mock(AgentToolApprovals.class);
        when(approvals.canPropose(USER, RUN)).thenReturn(true);
        when(approvals.await(eq(USER), eq(RUN), eq("fake_write"), any())).thenReturn(true);
        AgentPermissionGrants permissions = mock(AgentPermissionGrants.class);
        when(permissions.autoApproves(USER, "fake_write")).thenReturn(false);

        String result = writeExecutor(approvals, permissions).execute(writeRequest(), null);

        assertThat(result).isEqualTo("executed:hi");
        verify(approvals).await(eq(USER), eq(RUN), eq("fake_write"), any());
        verify(permissions, never()).recordAutoApproval(any(), any(), any(), any());
    }

    @Test
    void readCallStartsAndFinishesAnAutoAuditRowWithoutADialog() {
        AgentToolApprovals approvals = mock(AgentToolApprovals.class);
        when(approvals.canRead(USER, RUN)).thenReturn(true);
        AgentPermissionGrants permissions = mock(AgentPermissionGrants.class);
        AgentExecutionAuditStore auditStore = mock(AgentExecutionAuditStore.class);
        UUID row = UUID.randomUUID();
        when(auditStore.startToolCall(any(), any(), any(), any(), any())).thenReturn(row);

        // list_memories is a read: no dialog and AUTO attribution.
        String result = auditedExecutor("list_memories", approvals, permissions, auditStore, null)
                .execute(request("list_memories", "{}"), null);

        assertThat(result).isEqualTo("[]");
        verify(approvals, never()).await(any(), any(), any(), any());
        ArgumentCaptor<String> source = ArgumentCaptor.forClass(String.class);
        verify(auditStore).startToolCall(any(AgentExecutionAuditStore.ToolCallContext.class),
                eq("call-list_memories"), eq("list_memories"), eq("{}"), source.capture());
        assertThat(source.getValue()).isEqualTo("AUTO");
        verify(auditStore).finishToolCall(eq(row), eq(USER), eq("COMPLETED"), eq("[]"), isNull());
        verify(auditStore, never()).recordAudit(any(), any(), any(), any(), any());
    }

    @Test
    void deleteProposalIsRecordedAsCardOnlyAndNeverAsAnExecutedMutation() {
        AgentToolApprovals approvals = mock(AgentToolApprovals.class);
        when(approvals.canPropose(USER, RUN)).thenReturn(true);
        AgentPermissionGrants permissions = mock(AgentPermissionGrants.class);
        AgentExecutionAuditStore auditStore = mock(AgentExecutionAuditStore.class);
        UUID row = UUID.randomUUID();
        when(auditStore.startToolCall(any(), any(), any(), any(), any())).thenReturn(row);

        String result = auditedExecutor("delete_memory", approvals, permissions, auditStore, null)
                .execute(request("delete_memory", "{\"memoryId\":\"x\"}"), null);

        assertThat(result).contains("CONFIRMATION_REQUIRED");
        ArgumentCaptor<String> source = ArgumentCaptor.forClass(String.class);
        verify(auditStore).startToolCall(any(AgentExecutionAuditStore.ToolCallContext.class), any(), any(), any(), source.capture());
        assertThat(source.getValue()).isEqualTo("DELETE_PROPOSAL");
        verify(auditStore).finishToolCall(eq(row), eq(USER), eq("PROPOSED"), isNull(), isNull());
        verify(auditStore).recordAudit(eq(USER), eq(RUN), eq("delete_memory"), anyString(), eq("DELETE_PROPOSAL"));
    }

    @Test
    void trustedAutomationMayPersistAdvisoryWritesWithoutALiveDialog() {
        AgentToolApprovals approvals = mock(AgentToolApprovals.class);
        AgentPermissionGrants permissions = mock(AgentPermissionGrants.class);
        var executors = ApprovedAgentTools.wrap(new AuditTools(), USER, null, approvals, "保存建议", permissions, true);
        ToolExecutor executor = executors.entrySet().stream()
                .filter(entry -> entry.getKey().name().equals("update_calendar_advice"))
                .findFirst().orElseThrow().getValue();

        String result = executor.execute(request("update_calendar_advice", "{\"advice\":\"下一步\"}"), null);

        assertThat(result).contains("SAVED");
        verify(approvals, never()).await(any(), any(), any(), any());
        verify(approvals, never()).canPropose(any(), any());
    }

    @Test
    void fencedLeaseRecordsNotExecutedWithoutClaimingSuccess() {
        AgentToolApprovals approvals = mock(AgentToolApprovals.class);
        when(approvals.canRead(USER, RUN)).thenReturn(true);
        AgentPermissionGrants permissions = mock(AgentPermissionGrants.class);
        AgentExecutionAuditStore auditStore = mock(AgentExecutionAuditStore.class);
        UUID row = UUID.randomUUID();
        when(auditStore.startToolCall(any(), any(), any(), any(), any())).thenReturn(row);
        AgentStreamLease lease = new AgentStreamLease();
        lease.fence();

        String result = auditedExecutor("list_memories", approvals, permissions, auditStore, lease)
                .execute(request("list_memories", "{}"), null);

        assertThat(result).contains("RUN_STOPPED");
        verify(auditStore).finishToolCall(eq(row), eq(USER), eq("NOT_EXECUTED"), isNull(), eq("RUN_STOPPED"));
    }
}
