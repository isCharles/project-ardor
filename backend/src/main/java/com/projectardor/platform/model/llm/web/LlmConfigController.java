package com.projectardor.platform.model.llm.web;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.projectardor.platform.identity.auth.security.ArdorPrincipal;
import com.projectardor.platform.model.llm.service.LlmConfigService;
import com.projectardor.platform.model.llm.service.LlmConnectionTestService;
import com.projectardor.platform.model.llm.service.LlmModelDiscoveryService;
import com.projectardor.platform.usage.QuotaProtected;
import com.projectardor.platform.usage.UsageFeature;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/settings/llm")
public class LlmConfigController {

    private final LlmConfigService service;
    private final LlmConnectionTestService connectionTestService;
    private final LlmModelDiscoveryService modelDiscoveryService;

    public LlmConfigController(LlmConfigService service, LlmConnectionTestService connectionTestService,
            LlmModelDiscoveryService modelDiscoveryService) {
        this.service = service;
        this.connectionTestService = connectionTestService;
        this.modelDiscoveryService = modelDiscoveryService;
    }

    @GetMapping
    public LlmConfigResponse get(@AuthenticationPrincipal ArdorPrincipal principal) {
        return service.get(principal.userId());
    }

    @PutMapping
    public LlmConfigResponse update(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @Valid @RequestBody LlmConfigUpdateRequest request) {
        return service.upsert(principal.userId(), request);
    }

    @PostMapping("/test")
    @QuotaProtected(UsageFeature.CONNECTION_TEST)
    public LlmConnectionTestResponse testConnection(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @Valid @RequestBody LlmConfigUpdateRequest request) {
        return connectionTestService.test(principal.userId(), request);
    }

    @PostMapping("/models")
    public LlmModelListResponse listModels(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @Valid @RequestBody LlmModelListRequest request) {
        return new LlmModelListResponse(modelDiscoveryService.list(principal.userId(), request));
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal ArdorPrincipal principal) {
        service.delete(principal.userId());
    }
}
