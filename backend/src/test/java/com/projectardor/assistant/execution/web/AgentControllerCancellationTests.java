package com.projectardor.assistant.execution.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import com.projectardor.assistant.memory.service.AgentMemoryService;
import com.projectardor.assistant.execution.service.AgentMessageQueue;
import com.projectardor.assistant.execution.service.AgentPermissionGrants;
import com.projectardor.assistant.execution.service.CareerAgentService;
import com.projectardor.platform.identity.auth.domain.UserAccount;
import com.projectardor.platform.identity.auth.domain.UserRole;
import com.projectardor.platform.identity.auth.repository.UserAccountRepository;
import com.projectardor.platform.identity.auth.security.ArdorPrincipal;
import com.projectardor.common.web.ApiExceptionHandler;
import com.projectardor.common.web.ResourceNotFoundException;
import com.projectardor.config.SecurityConfig;

/** HTTP contract for {@code POST /api/agent/runs/{requestId}/cancel}: 202 + truthful state, 404, idempotency. */
class AgentControllerCancellationTests {

    private static AnnotationConfigWebApplicationContext context;
    private static MockMvc mvc;
    private static CareerAgentService agentService;

    private final UUID owner = UUID.randomUUID();
    private final ArdorPrincipal principal = new ArdorPrincipal(owner, "cancel@ardor.invalid", "", true, UserRole.USER);

    @BeforeAll
    static void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestWebConfig.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        agentService = context.getBean(CareerAgentService.class);
    }

    @AfterAll
    static void tearDown() {
        if (context != null) context.close();
    }

    @BeforeEach
    void resetAgent() {
        reset(agentService);
    }

    @Test
    void cancelReturns202WithTruthfulRunStateAndPrincipalOwner() throws Exception {
        UUID requestId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        when(agentService.cancelRun(owner, requestId)).thenReturn(new AgentRunResponse(requestId, conversationId,
                "合成消息", "INTERRUPTED", "状态待确认", "AGENT_RUN_CANCELLED",
                "已停止本轮；当前服务无法确认远端模型执行是否已终止，已开始的操作请核验结果。",
                false, Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:01Z")));

        mvc.perform(post("/api/agent/runs/{requestId}/cancel", requestId).with(user(principal)).with(csrf()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(requestId.toString()))
                .andExpect(jsonPath("$.status").value("INTERRUPTED"))
                .andExpect(jsonPath("$.errorCode").value("AGENT_RUN_CANCELLED"))
                .andExpect(jsonPath("$.retryable").value(false));
        // The owner always comes from the authenticated principal, never from the request.
        verify(agentService).cancelRun(owner, requestId);
    }

    @Test
    void cancelOfUnknownOrForeignRunStays404() throws Exception {
        UUID requestId = UUID.randomUUID();
        when(agentService.cancelRun(owner, requestId)).thenThrow(new ResourceNotFoundException("请求记录不存在"));

        mvc.perform(post("/api/agent/runs/{requestId}/cancel", requestId).with(user(principal)).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void repeatedCancelReturnsTheSameTerminalStateIdempotently() throws Exception {
        UUID requestId = UUID.randomUUID();
        AgentRunResponse completed = new AgentRunResponse(requestId, UUID.randomUUID(), "合成消息",
                "COMPLETED", "已完成", null, null, false,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:02:00Z"));
        when(agentService.cancelRun(owner, requestId)).thenReturn(completed);

        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post("/api/agent/runs/{requestId}/cancel", requestId).with(user(principal)).with(csrf()))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.status").value("COMPLETED"));
        }
        verify(agentService, times(2)).cancelRun(owner, requestId);
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import(SecurityConfig.class)
    static class TestWebConfig {

        @Bean
        AgentController agentController() {
            return new AgentController(agentService(), mock(AgentMemoryService.class),
                    mock(AgentMessageQueue.class), mock(AgentPermissionGrants.class));
        }

        @Bean
        CareerAgentService agentService() {
            return mock(CareerAgentService.class);
        }

        @Bean
        ApiExceptionHandler apiExceptionHandler() {
            return new ApiExceptionHandler();
        }

        @Bean
        UserAccountRepository userAccountRepository() {
            var users = mock(UserAccountRepository.class);
            when(users.findById(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(Optional.of(UserAccount.create("cancel@ardor.invalid", "")));
            return users;
        }
    }
}
