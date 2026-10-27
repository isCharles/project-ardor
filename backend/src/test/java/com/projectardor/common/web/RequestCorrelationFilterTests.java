package com.projectardor.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class RequestCorrelationFilterTests {

    private final RequestCorrelationFilter filter = new RequestCorrelationFilter();

    @AfterEach
    void cleanUp() {
        MDC.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void setsRequestIdAndUserIdInMdc() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getHeader("X-Request-Id")).thenReturn(null);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user-123", "pass", java.util.List.of()));

        boolean[] chainCalled = {false};
        FilterChain chain = (req, res) -> {
            chainCalled[0] = true;
            assertThat(MDC.get("requestId")).isNotBlank();
            assertThat(MDC.get("userId")).isEqualTo("user-123");
        };

        filter.doFilter(request, response, chain);

        assertThat(chainCalled[0]).isTrue();
        assertThat(MDC.get("requestId")).isNull();
        assertThat(MDC.get("userId")).isNull();
    }

    @Test
    void reusesIncomingRequestIdHeader() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getHeader("X-Request-Id")).thenReturn("incoming-id-abc");

        FilterChain chain = (req, res) -> assertThat(MDC.get("requestId")).isEqualTo("incoming-id-abc");

        filter.doFilter(request, response, chain);

        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void clearsMdcEvenWhenChainThrows() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getHeader("X-Request-Id")).thenReturn(null);

        FilterChain chain = (req, res) -> { throw new RuntimeException("boom"); };

        try {
            filter.doFilter(request, response, chain);
        } catch (RuntimeException expected) {
            // expected
        }

        assertThat(MDC.get("requestId")).isNull();
        assertThat(MDC.get("userId")).isNull();
    }
}
