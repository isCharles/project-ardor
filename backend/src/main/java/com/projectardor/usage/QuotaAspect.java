package com.projectardor.usage;

import java.util.Arrays;
import java.util.UUID;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

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
        UUID requestId = null;
        if (protectedFeature.idempotentRequest()) {
            QuotaRequestId request = Arrays.stream(arguments)
                    .filter(QuotaRequestId.class::isInstance)
                    .map(QuotaRequestId.class::cast)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("幂等额度接口缺少请求 ID 参数"));
            requestId = request.requestId();
            if (requestId == null && !protectedFeature.optionalRequestId()) {
                throw new IllegalArgumentException("流式请求缺少 requestId");
            }
        }
        quotas.consume(principal.userId(), protectedFeature.value(), requestId);
        return joinPoint.proceed();
    }
}
