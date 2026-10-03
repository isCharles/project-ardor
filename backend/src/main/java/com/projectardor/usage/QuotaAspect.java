package com.projectardor.usage;

import java.util.Arrays;
import java.util.UUID;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import com.projectardor.agent.web.AgentMessageRequest;
import com.projectardor.auth.security.ArdorPrincipal;

@Aspect
@Component
public class QuotaAspect {
    private final QuotaService quotas;

    public QuotaAspect(QuotaService quotas) {
        this.quotas = quotas;
    }

    @Around("@annotation(protectedFeature)")
    public Object enforce(ProceedingJoinPoint joinPoint, QuotaProtected protectedFeature) throws Throwable {
        Object[] arguments = joinPoint.getArgs();
        ArdorPrincipal principal = Arrays.stream(arguments)
                .filter(ArdorPrincipal.class::isInstance)
                .map(ArdorPrincipal.class::cast)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("受额度保护的接口缺少用户身份"));
        UUID requestId = protectedFeature.idempotentRequest() ? Arrays.stream(arguments)
                .filter(AgentMessageRequest.class::isInstance)
                .map(AgentMessageRequest.class::cast)
                .map(AgentMessageRequest::requestId)
                .filter(java.util.Objects::nonNull)
                .findFirst().orElse(null) : null;
        quotas.consume(principal.userId(), protectedFeature.value(), requestId);
        return joinPoint.proceed();
    }
}
