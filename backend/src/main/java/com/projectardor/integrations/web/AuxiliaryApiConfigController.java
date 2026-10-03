package com.projectardor.integrations.web;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.projectardor.auth.security.ArdorPrincipal;
import com.projectardor.integrations.domain.AuxiliaryServiceType;
import com.projectardor.integrations.service.AuxiliaryApiConfigService;
import com.projectardor.integrations.service.AuxiliaryConnectionTestService;
import com.projectardor.usage.QuotaProtected;
import com.projectardor.usage.UsageFeature;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/settings/integrations")
public class AuxiliaryApiConfigController {

    private final AuxiliaryApiConfigService service;
    private final AuxiliaryConnectionTestService connectionTestService;

    public AuxiliaryApiConfigController(
            AuxiliaryApiConfigService service,
            AuxiliaryConnectionTestService connectionTestService) {
        this.service = service;
        this.connectionTestService = connectionTestService;
    }

    @GetMapping
    public List<AuxiliaryApiConfigResponse> list(@AuthenticationPrincipal ArdorPrincipal principal) {
        return service.list(principal.userId());
    }

    @PutMapping("/{serviceType}")
    public AuxiliaryApiConfigResponse update(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable AuxiliaryServiceType serviceType,
            @Valid @RequestBody AuxiliaryApiConfigRequest request) {
        return service.upsert(principal.userId(), serviceType, request);
    }

    @PostMapping("/{serviceType}/test")
    @QuotaProtected(UsageFeature.CONNECTION_TEST)
    public AuxiliaryConnectionTestResponse test(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable AuxiliaryServiceType serviceType,
            @Valid @RequestBody AuxiliaryApiConfigRequest request) {
        return connectionTestService.test(principal.userId(), serviceType, request);
    }

    @DeleteMapping("/{serviceType}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable AuxiliaryServiceType serviceType) {
        service.delete(principal.userId(), serviceType);
    }
}
