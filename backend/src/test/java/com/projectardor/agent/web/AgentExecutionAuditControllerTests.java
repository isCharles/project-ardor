package com.projectardor.agent.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
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

import com.projectardor.agent.service.AgentExecutionAuditStore;
import com.projectardor.agent.service.AgentRunStore;
import com.projectardor.platform.identity.auth.domain.UserAccount;
import com.projectardor.platform.identity.auth.domain.UserRole;
import com.projectardor.platform.identity.auth.repository.UserAccountRepository;
import com.projectardor.platform.identity.auth.security.ArdorPrincipal;
import com.projectardor.common.web.ApiExceptionHandler;
import com.projectardor.common.web.ResourceNotFoundException;
import com.projectardor.config.SecurityConfig;

/** HTTP contract for the read-only run tool-call/audit views: owner 200, foreign/unknown 404. */
class AgentExecutionAuditControllerTests {

    private static AnnotationConfigWebApplicationContext context;
    private static MockMvc mvc;
    private static AgentRunStore runs;
    private static AgentExecutionAuditStore audit;

    private final UUID owner = UUID.randomUUID();
    private final ArdorPrincipal principal = new ArdorPrincipal(owner, "audit@ardor.invalid", "", true, UserRole.USER);

    @BeforeAll
    static void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestWebConfig.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        runs = context.getBean(AgentRunStore.class);
        audit = context.getBean(AgentExecutionAuditStore.class);
    }

    @AfterAll
    static void tearDown() {
        if (context != null) context.close();
    }

    @BeforeEach
    void resetMocks() {
        reset(runs, audit);
    }

    @Test
    void ownerReadsStructuredToolCallsAndAuditFacts() throws Exception {
        UUID requestId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        when(runs.get(owner, requestId)).thenReturn(run(requestId, messageId));
        when(audit.toolCalls(owner, requestId)).thenReturn(List.of(new AgentExecutionAuditStore.ToolCall(
                UUID.randomUUID(), requestId, messageId, "call-1", "list_resumes", "resumes", "READ",
                "AUTO", "COMPLETED", null, "a".repeat(64), "b".repeat(64), UUID.randomUUID().toString(),
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:01Z"))));
        when(audit.proposals(owner, requestId)).thenReturn(List.of());
        when(audit.auditEvents(owner, requestId)).thenReturn(List.of());

        mvc.perform(get("/api/agent/runs/{requestId}/tool-calls", requestId).with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].toolName").value("list_resumes"))
                .andExpect(jsonPath("$[0].approvalSource").value("AUTO"))
                .andExpect(jsonPath("$[0].status").value("COMPLETED"));
        verify(audit).toolCalls(owner, requestId);

        mvc.perform(get("/api/agent/runs/{requestId}/audit", requestId).with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(requestId.toString()))
                .andExpect(jsonPath("$.assistantMessageId").value(messageId.toString()))
                .andExpect(jsonPath("$.toolCalls[0].toolName").value("list_resumes"));
    }

    @Test
    void unknownOrForeignRunStays404WithoutReadingAuditRows() throws Exception {
        UUID requestId = UUID.randomUUID();
        when(runs.get(owner, requestId)).thenThrow(new ResourceNotFoundException("请求记录不存在"));

        mvc.perform(get("/api/agent/runs/{requestId}/tool-calls", requestId).with(user(principal)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(get("/api/agent/runs/{requestId}/audit", requestId).with(user(principal)))
                .andExpect(status().isNotFound());

        // Ownership is checked before any audit read, so a foreign run leaks no row counts.
        verify(audit, never()).toolCalls(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(audit, never()).proposals(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private static AgentRunStore.AgentRun run(UUID requestId, UUID assistantMessageId) {
        return new AgentRunStore.AgentRun(requestId, UUID.randomUUID(), "合成消息", "COMPLETED", "已完成",
                null, null, false, true, assistantMessageId,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:01:00Z"));
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import(SecurityConfig.class)
    static class TestWebConfig {

        @Bean
        AgentExecutionAuditController agentExecutionAuditController() {
            return new AgentExecutionAuditController(runs(), executionAuditStore());
        }

        @Bean
        AgentRunStore runs() {
            return mock(AgentRunStore.class);
        }

        @Bean
        AgentExecutionAuditStore executionAuditStore() {
            return mock(AgentExecutionAuditStore.class);
        }

        @Bean
        ApiExceptionHandler apiExceptionHandler() {
            return new ApiExceptionHandler();
        }

        @Bean
        UserAccountRepository userAccountRepository() {
            var users = mock(UserAccountRepository.class);
            when(users.findById(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(Optional.of(UserAccount.create("audit@ardor.invalid", "")));
            return users;
        }
    }
}
