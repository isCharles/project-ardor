package com.projectardor.platform.identity.profile.web;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.*;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import com.projectardor.config.SecurityConfig;
import com.projectardor.common.web.ApiExceptionHandler;
import com.projectardor.platform.identity.auth.domain.*;
import com.projectardor.platform.identity.auth.repository.UserAccountRepository;
import com.projectardor.platform.identity.auth.security.ArdorPrincipal;
import com.projectardor.platform.identity.profile.service.ProfileAvatarService;

class ProfileAvatarControllerTests {
    AnnotationConfigWebApplicationContext context;
    MockMvc mvc;
    ProfileAvatarService service;
    final UUID owner = UUID.randomUUID();
    @BeforeEach void setup() {
        context = new AnnotationConfigWebApplicationContext(); context.setServletContext(new MockServletContext());
        context.register(Config.class); context.refresh();
        service = context.getBean(ProfileAvatarService.class);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    @AfterEach void close() { context.close(); }
    ArdorPrincipal principal() { return new ArdorPrincipal(owner, "synthetic@ardor.invalid", "", true, UserRole.USER); }
    @Test void requiresAuthenticationAndCsrfForMutations() throws Exception {
        mvc.perform(get("/api/profile/avatar")).andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/profile/avatar").file(new MockMultipartFile("file", "avatar.png", "image/png", new byte[]{1}))
                .with(request -> { request.setMethod("PUT"); return request; }).with(user(principal()))
                .param("expectedUserId", owner.toString())).andExpect(status().isForbidden());
        mvc.perform(delete("/api/profile/avatar").with(user(principal())).param("expectedUserId", owner.toString()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    @Test void readsOnlyPrincipalAndReturnsNoStore() throws Exception {
        when(service.read(owner)).thenReturn(new ProfileAvatarService.View(owner, null, null));
        mvc.perform(get("/api/profile/avatar").with(user(principal())).param("userId", UUID.randomUUID().toString()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.userId").value(owner.toString()));
        verify(service).read(owner);
    }
    @Test void authenticatedMultipartAndDeleteForwardPrincipalOwnership() throws Exception {
        byte[] image = {1,2,3};
        when(service.upload(eq(owner), eq(owner), any())).thenReturn(new ProfileAvatarService.View(owner, null, null));
        when(service.remove(owner, owner)).thenReturn(new ProfileAvatarService.View(owner, null, null));
        mvc.perform(multipart("/api/profile/avatar").file(new MockMultipartFile("file", "avatar.png", "image/png", image))
                .with(request -> { request.setMethod("PUT"); return request; }).with(user(principal())).with(csrf())
                .param("expectedUserId", owner.toString())).andExpect(status().isOk());
        verify(service).upload(eq(owner), eq(owner), argThat(bytes -> java.util.Arrays.equals(bytes, image)));
        mvc.perform(delete("/api/profile/avatar").with(user(principal())).with(csrf()).param("expectedUserId", owner.toString()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        verify(service).remove(owner, owner);
    }
    @Configuration @EnableWebMvc @EnableWebSecurity @Import(SecurityConfig.class)
    static class Config {
        @Bean ProfileAvatarService service() { return mock(ProfileAvatarService.class); }
        @Bean ProfileAvatarController controller(ProfileAvatarService service) { return new ProfileAvatarController(service); }
        @Bean ApiExceptionHandler errors() { return new ApiExceptionHandler(); }
        @Bean UserAccountRepository users() {
            var users = mock(UserAccountRepository.class);
            when(users.findById(any())).thenReturn(Optional.of(UserAccount.create("fixture@ardor.invalid", "")));
            return users;
        }
    }
}
