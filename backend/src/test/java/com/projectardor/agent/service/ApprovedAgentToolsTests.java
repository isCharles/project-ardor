package com.projectardor.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.agent.tool.ToolExecutionRequest;

class ApprovedAgentToolsTests {

    static class FakeTools {
        @Tool(name = "fake_write", value = "写操作")
        public String write(@P("值") String value) {
            return "executed:" + value;
        }
    }

    private static final UUID USER = UUID.randomUUID();
    private static final UUID RUN = UUID.randomUUID();

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
    void acceptsMemoryContentThatRepeatsTheUserWords() {
        assertThat(ApprovedAgentTools.memoryWriteRejection("add_memory",
                "{\"memory\":\"现在研二了\"}", "现在研二了")).isNull();
        assertThat(ApprovedAgentTools.memoryWriteRejection("update_memory",
                "{\"memoryId\":\"abc\",\"content\":\"现在研二了\"}", "现在研二了")).isNull();
    }

    @Test
    void rejectsRewrittenMemoryBeforeAskingForApproval() {
        String rejection = ApprovedAgentTools.memoryWriteRejection("update_memory",
                "{\"memoryId\":\"abc\",\"content\":\"用户目前是研二（研究生二年级）\"}", "现在研二了");
        assertThat(rejection).contains("逐字引用").contains("不要继续换说法");
    }

    @Test
    void skipsThePrecheckWhenTheTurnTextIsUnknown() {
        assertThat(ApprovedAgentTools.memoryWriteRejection("add_memory",
                "{\"memory\":\"改写过的内容\"}", null)).isNull();
    }

    @Test
    void ignoresToolsWithoutMemoryArguments() {
        assertThat(ApprovedAgentTools.memoryWriteRejection("create_calendar_task",
                "{\"title\":\"组会\"}", "帮我加个组会")).isNull();
    }

    @Test
    void readsEscapedStringArguments() {
        assertThat(ApprovedAgentTools.argumentString(
                "{\"memory\" : \"他说\\\"好\\\"\\n下一行\"}", "memory")).isEqualTo("他说\"好\"\n下一行");
        assertThat(ApprovedAgentTools.argumentString("{\"content\":\"现在研二了\"}", "memory")).isNull();
    }
}
