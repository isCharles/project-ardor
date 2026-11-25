package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import com.projectardor.assistant.tools.CareerAgentTools.PendingConfirmation;
import com.projectardor.config.SecurityConfig;
import com.projectardor.platform.identity.auth.domain.UserAccount;
import com.projectardor.platform.identity.auth.domain.UserRole;
import com.projectardor.platform.identity.auth.domain.UserStatus;
import com.projectardor.platform.identity.auth.repository.UserAccountRepository;
import com.projectardor.platform.identity.auth.security.ArdorPrincipal;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class AgentDeletionCompletionFilterTests {
    private static final UUID OWNER = UUID.randomUUID();
    private static final ArdorPrincipal PRINCIPAL = new ArdorPrincipal(
            OWNER, "deletion-filter@ardor.invalid", "unused", true, UserRole.USER);
    private static AnnotationConfigWebApplicationContext context;
    private static MockMvc mvc;
    private static AgentDeletionConfirmations confirmations;

    @BeforeAll
    static void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestWebConfig.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        confirmations = context.getBean(AgentDeletionConfirmations.class);
    }

    @AfterAll
    static void tearDown() {
        if (context != null) context.close();
    }

    @Test
    void securityChainReleasesTheOwnersGateAfterAuthenticatedBusinessDelete() throws Exception {
        UUID run = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        String endpoint = "/api/memory-cards/" + target;
        PendingConfirmation proposal = new PendingConfirmation("memory_card", target, "Fixture", "Fixture detail",
                endpoint, null, null, UUID.randomUUID());
        CompletableFuture<Boolean> waiting = CompletableFuture.supplyAsync(() -> confirmations.await(OWNER, run, proposal));

        assertThatThrownBy(() -> waiting.get(50, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        mvc.perform(delete(endpoint).with(user(PRINCIPAL)).with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(waiting.get(2, TimeUnit.SECONDS)).isTrue();
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import(SecurityConfig.class)
    static class TestWebConfig {
        @Bean
        AgentDeletionConfirmations deletionConfirmations() {
            return new AgentDeletionConfirmations(Duration.ofSeconds(2));
        }

        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder().build();
        }

        @Bean
        UserAccountRepository userAccountRepository() {
            var users = mock(UserAccountRepository.class);
            var account = mock(UserAccount.class);
            when(account.getPasswordHash()).thenReturn("unused");
            when(account.getStatus()).thenReturn(UserStatus.ACTIVE);
            when(users.findById(OWNER)).thenReturn(Optional.of(account));
            return users;
        }

        @Bean
        DeleteProbeController deleteProbeController() {
            return new DeleteProbeController();
        }
    }

    @RestController
    static class DeleteProbeController {
        @DeleteMapping("/api/memory-cards/{id}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void delete(@PathVariable UUID id) {}
    }
}
