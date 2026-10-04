package com.projectardor.auth.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.web.context.SecurityContextRepository;

import com.projectardor.auth.domain.UserRole;
import com.projectardor.auth.security.ArdorPrincipal;
import com.projectardor.auth.service.AuthService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

class AuthControllerPasswordTests {

    @Test
    void invalidatesCurrentSessionAfterPasswordChange() {
        AuthService service = mock(AuthService.class);
        AuthController controller = new AuthController(
                service, mock(AuthenticationManager.class), mock(SecurityContextRepository.class));
        UUID userId = UUID.randomUUID();
        ArdorPrincipal principal = new ArdorPrincipal(userId, "test@example.com", "hash", true, UserRole.USER);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpSession currentSession = mock(HttpSession.class);
        when(request.getSession(false)).thenReturn(currentSession);

        controller.changePassword(new ChangePasswordRequest("old-password", "new-password"), principal, request);

        verify(service).changePassword(userId, "old-password", "new-password");
        verify(currentSession).invalidate();
    }
}
