package com.projectardor.usage;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import com.projectardor.auth.domain.UserRole;
import com.projectardor.auth.security.ArdorPrincipal;
import com.projectardor.config.SecurityConfig;

class UsageAdminAuthorizationTests {
    private static AnnotationConfigWebApplicationContext context;
    private static MockMvc mvc;

    @BeforeAll
    static void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestWebConfig.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterAll
    static void tearDown() {
        if (context != null) context.close();
    }

    @Test
    void regularUserCannotReadOrModifyPolicies() throws Exception {
        var user = user(principal(UserRole.USER));
        mvc.perform(get("/api/admin/usage-policies").with(user))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/usage-policies/AGENT_CHAT")
                .with(user(principal(UserRole.USER))).with(csrf())
                .contentType("application/json")
                .content("{\"freeMonthlyLimit\":5,\"memberMonthlyLimit\":500,\"userMinuteLimit\":8,\"globalMinuteLimit\":30}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void regularUserCannotChangeMembership() throws Exception {
        mvc.perform(put("/api/admin/users/{userId}/membership", UUID.randomUUID())
                .with(user(principal(UserRole.USER))).with(csrf())
                .contentType("application/json").content("{\"tier\":\"MEMBER\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanReachPolicyEndpoint() throws Exception {
        mvc.perform(get("/api/admin/usage-policies").with(user(principal(UserRole.ADMIN))))
                .andExpect(status().isOk());
    }

    private static ArdorPrincipal principal(UserRole role) {
        return new ArdorPrincipal(UUID.randomUUID(), "test@example.com", "", true, role);
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @org.springframework.context.annotation.Import(SecurityConfig.class)
    static class TestWebConfig {
        @Bean
        UsageController usageController() {
            return new UsageController(org.mockito.Mockito.mock(QuotaService.class),
                    org.mockito.Mockito.mock(UsagePolicyRepository.class),
                    org.mockito.Mockito.mock(MembershipService.class));
        }

        @Bean
        com.projectardor.auth.repository.UserAccountRepository userAccountRepository() {
            var users = org.mockito.Mockito.mock(com.projectardor.auth.repository.UserAccountRepository.class);
            org.mockito.Mockito.when(users.findById(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(java.util.Optional.of(com.projectardor.auth.domain.UserAccount.create("test@example.com", "")));
            return users;
        }
    }
}
