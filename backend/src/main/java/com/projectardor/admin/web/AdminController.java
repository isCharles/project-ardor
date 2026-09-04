package com.projectardor.admin.web;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.projectardor.admin.domain.SystemApiServiceType;
import com.projectardor.admin.service.AdminConnectionTestService;
import com.projectardor.admin.service.AdminOverviewService;
import com.projectardor.admin.service.AdminUserService;
import com.projectardor.admin.service.SystemApiConfigService;
import com.projectardor.auth.security.ArdorPrincipal;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final AdminOverviewService overviewService;
    private final AdminUserService userService;
    private final SystemApiConfigService configService;
    private final AdminConnectionTestService connectionTestService;

    public AdminController(AdminOverviewService overviewService, AdminUserService userService,
            SystemApiConfigService configService, AdminConnectionTestService connectionTestService) {
        this.overviewService = overviewService;
        this.userService = userService;
        this.configService = configService;
        this.connectionTestService = connectionTestService;
    }

    @GetMapping("/overview")
    public AdminOverviewResponse overview() { return overviewService.get(); }

    @GetMapping("/users")
    public List<AdminUserResponse> users() { return userService.list(); }

    @PatchMapping("/users/{userId}")
    public AdminUserResponse updateUser(@AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable UUID userId, @RequestBody AdminUserUpdateRequest request) {
        return userService.update(principal.userId(), userId, request);
    }

    @GetMapping("/api-configs")
    public List<AdminApiConfigResponse> configs() { return configService.list(); }

    @PutMapping("/api-configs/{serviceType}")
    public AdminApiConfigResponse updateConfig(@AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable SystemApiServiceType serviceType,
            @Valid @RequestBody AdminApiConfigRequest request) {
        return configService.upsert(principal.userId(), serviceType, request);
    }

    @PostMapping("/api-configs/{serviceType}/test")
    public Object testConfig(@AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable SystemApiServiceType serviceType,
            @Valid @RequestBody AdminApiConfigRequest request) {
        return connectionTestService.test(principal.userId(), serviceType, request);
    }

    @DeleteMapping("/api-configs/{serviceType}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteConfig(@PathVariable SystemApiServiceType serviceType) {
        configService.delete(serviceType);
    }
}
