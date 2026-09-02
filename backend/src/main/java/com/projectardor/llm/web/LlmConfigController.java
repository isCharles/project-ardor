package com.projectardor.llm.web;

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

import com.projectardor.auth.security.ArdorPrincipal;
import com.projectardor.llm.service.LlmConfigService;
import com.projectardor.llm.service.LlmConnectionTestService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/settings/llm")
public class LlmConfigController {

    private final LlmConfigService service;
    private final LlmConnectionTestService connectionTestService;

    public LlmConfigController(LlmConfigService service, LlmConnectionTestService connectionTestService) {
        this.service = service;
        this.connectionTestService = connectionTestService;
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
    public LlmConnectionTestResponse testConnection(
            @AuthenticationPrincipal ArdorPrincipal principal,
            @Valid @RequestBody LlmConfigUpdateRequest request) {
        return connectionTestService.test(principal.userId(), request);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal ArdorPrincipal principal) {
        service.delete(principal.userId());
    }
}
