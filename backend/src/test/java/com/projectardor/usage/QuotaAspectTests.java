package com.projectardor.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import com.projectardor.agent.web.AgentMessageRequest;
import com.projectardor.auth.domain.UserRole;
import com.projectardor.auth.security.ArdorPrincipal;
import com.projectardor.learning.web.LearningPlanCreateRequest;

class QuotaAspectTests {
    @Test
    void annotationChargesAuthenticatedUserAndKeepsRequestId() {
        QuotaService quotas = mock(QuotaService.class);
        var target = new ProtectedOperation();
        var factory = new AspectJProxyFactory(target);
        factory.addAspect(new QuotaAspect(quotas));
        ProtectedOperation operation = factory.getProxy();
        UUID userId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        var principal = new ArdorPrincipal(userId, "user@example.com", "", true, UserRole.USER);

        assertThat(operation.call(principal,
                new AgentMessageRequest(null, "hi", null, null, null, requestId))).isEqualTo("ok");
        verify(quotas).consume(userId, UsageFeature.AGENT_CHAT, requestId);
    }

    @Test
    void rejectedQuotaStopsTheOperation() {
        QuotaService quotas = mock(QuotaService.class);
        var target = new ProtectedOperation();
        var factory = new AspectJProxyFactory(target);
        factory.addAspect(new QuotaAspect(quotas));
        ProtectedOperation operation = factory.getProxy();
        UUID userId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        var principal = new ArdorPrincipal(userId, "user@example.com", "", true, UserRole.USER);
        doThrow(new QuotaExceededException(UsageFeature.AGENT_CHAT,
                QuotaStore.Decision.MONTHLY_LIMIT, java.time.Instant.now()))
                .when(quotas).consume(userId, UsageFeature.AGENT_CHAT, requestId);

        assertThatThrownBy(() -> operation.call(principal,
                new AgentMessageRequest(null, "hi", null, null, null, requestId)))
                .isInstanceOf(QuotaExceededException.class);
        assertThat(target.invocations).isZero();
    }

    @Test
    void idempotentEndpointRejectsMissingRequestIdBeforeCharge() {
        QuotaService quotas = mock(QuotaService.class);
        var target = new ProtectedOperation();
        var factory = new AspectJProxyFactory(target);
        factory.addAspect(new QuotaAspect(quotas));
        ProtectedOperation operation = factory.getProxy();
        var principal = new ArdorPrincipal(UUID.randomUUID(), "user@example.com", "", true, UserRole.USER);

        assertThatThrownBy(() -> operation.call(principal,
                new AgentMessageRequest(null, "hi", null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestId");
        assertThat(target.invocations).isZero();
        org.mockito.Mockito.verifyNoInteractions(quotas);
    }

    @Test
    void optionalRequestIdKeepsOldClientsAndDeduplicatesNewOnes() {
        QuotaService quotas = mock(QuotaService.class);
        var factory = new AspectJProxyFactory(new OptionalRequestOperation());
        factory.addAspect(new QuotaAspect(quotas));
        OptionalRequestOperation operation = factory.getProxy();
        UUID userId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        var principal = new ArdorPrincipal(userId, "user@example.com", "", true, UserRole.USER);

        operation.call(principal, new LearningPlanCreateRequest("JVM", null, null, null, null, null));
        operation.call(principal, new LearningPlanCreateRequest("JVM", null, null, null, null, requestId));

        verify(quotas).consume(userId, UsageFeature.LEARNING_PLAN, null);
        verify(quotas).consume(userId, UsageFeature.LEARNING_PLAN, requestId);
    }

    static class ProtectedOperation {
        int invocations;

        @QuotaProtected(value = UsageFeature.AGENT_CHAT, idempotentRequest = true)
        public String call(ArdorPrincipal principal, AgentMessageRequest request) {
            invocations++;
            return "ok";
        }
    }

    static class OptionalRequestOperation {
        @QuotaProtected(value = UsageFeature.LEARNING_PLAN, idempotentRequest = true, optionalRequestId = true)
        public void call(ArdorPrincipal principal, LearningPlanCreateRequest request) {}
    }
}
