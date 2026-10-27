package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.projectardor.assistant.tools.CareerAgentTools;
import com.projectardor.common.web.ResourceNotFoundException;
import com.projectardor.platform.model.llm.service.LlmConfigService;
import com.projectardor.platform.identity.profile.service.ProfileService;
import tools.jackson.databind.ObjectMapper;
import com.projectardor.assistant.conversation.service.AgentConversationStore;
import com.projectardor.assistant.conversation.service.ChatImageService;
import com.projectardor.assistant.memory.service.AgentMemoryService;
import com.projectardor.assistant.memory.service.AgentContextCompactor;
import com.projectardor.assistant.memory.service.MemoryEmbeddingService;

/** Endpoint-level cancellation wiring: no 500, owner-scoped lookup, truthful returned status. */
class CareerAgentCancellationTests {

    private final AgentRunStore runs = mock(AgentRunStore.class);
    private final CareerAgentService service = new CareerAgentService(
            mock(AgentConversationStore.class), runs, mock(LangChainModelFactory.class),
            mock(CareerAgentTools.class), mock(LlmConfigService.class), mock(AgentMemoryService.class),
            mock(AgentContextBuilder.class), mock(ProfileService.class), mock(ObjectMapper.class),
            mock(AgentStreamProgress.class), mock(AgentToolApprovals.class), mock(AgentPermissionGrants.class),
            mock(AgentExecutionAuditStore.class), mock(AgentTraceStore.class), mock(AgentModelCatalogService.class),
            mock(ChatImageService.class), mock(com.projectardor.platform.usage.UsageTokenStore.class), mock(AgentMetrics.class),
            Duration.ofSeconds(90), Duration.ofMinutes(10));

    @Test
    void terminalRunIsReportedAsIsWithoutStoppingAnythingAgain() {
        UUID user = UUID.randomUUID();
        UUID request = UUID.randomUUID();
        when(runs.get(user, request)).thenReturn(run(request, "FAILED", "AGENT_RUN_CANCELLED"));

        var response = service.cancelRun(user, request);

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.errorCode()).isEqualTo("AGENT_RUN_CANCELLED");
        verify(runs, never()).cancel(any(), any(), anyBoolean());
    }

    @Test
    void runningRunWithoutLocalExecutorIsInterruptedAndItsStateIsReturned() {
        UUID user = UUID.randomUUID();
        UUID request = UUID.randomUUID();
        when(runs.get(user, request)).thenReturn(run(request, "RUNNING", null));
        when(runs.cancel(user, request, false)).thenReturn(run(request, "INTERRUPTED", "AGENT_RUN_CANCELLED"));

        var response = service.cancelRun(user, request);

        assertThat(response.status()).isEqualTo("INTERRUPTED");
        assertThat(response.errorCode()).isEqualTo("AGENT_RUN_CANCELLED");
        verify(runs).cancel(user, request, false);
    }

    @Test
    void unknownOrForeignRunStaysNotFoundInsteadOfBecomingA500() {
        UUID user = UUID.randomUUID();
        UUID request = UUID.randomUUID();
        when(runs.get(user, request)).thenThrow(new ResourceNotFoundException("请求记录不存在"));

        assertThatThrownBy(() -> service.cancelRun(user, request)).isInstanceOf(ResourceNotFoundException.class);
        verify(runs, never()).cancel(any(), any(), anyBoolean());
    }

    private static AgentRunStore.AgentRun run(UUID request, String status, String errorCode) {
        return new AgentRunStore.AgentRun(request, UUID.randomUUID(), "合成消息", status,
                "RUNNING".equals(status) ? "正在理解" : "状态待确认", errorCode, null, false, false,
                Instant.now(), Instant.now());
    }
}
