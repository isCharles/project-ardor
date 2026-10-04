package com.projectardor.auth.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.projectardor.auth.repository.UserAccountRepository;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/** Existing sessions retain a snapshot of credentials; reject them after a password change. */
public class CredentialFreshnessFilter extends OncePerRequestFilter {

    private final UserAccountRepository users;

    public CredentialFreshnessFilter(UserAccountRepository users) {
        this.users = users;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getServletPath().equals("/api/auth/login")
                || request.getServletPath().equals("/api/auth/register")
                || request.getServletPath().equals("/api/auth/csrf");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof ArdorPrincipal principal
                && users.findById(principal.userId())
                        .filter(user -> user.getPasswordHash().equals(principal.passwordHash()) &&
                                user.getStatus() == com.projectardor.auth.domain.UserStatus.ACTIVE)
                        .isEmpty()) {
            HttpSession session = request.getSession(false);
            if (session != null) session.invalidate();
            SecurityContextHolder.clearContext();
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"code\":\"SESSION_EXPIRED\",\"message\":\"密码已修改，请重新登录\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
