package com.projectardor.common.web;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Populates MDC with request-scoped correlation fields so that structured log lines
 * can be linked back to the originating HTTP request and authenticated user.
 *
 * <p>Fields: {@code requestId} (random per-request UUID) and {@code userId}
 * (from the Spring Security context when available). Both are cleared after the
 * request completes to prevent leakage across pooled threads.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class RequestCorrelationFilter extends OncePerRequestFilter {

    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String existing = request.getHeader(REQUEST_ID_HEADER);
        String requestId = existing != null && !existing.isBlank() ? existing : UUID.randomUUID().toString();
        MDC.put("requestId", requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            MDC.put("userId", auth.getName());
        }
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("requestId");
            MDC.remove("userId");
        }
    }
}
