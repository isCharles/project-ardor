package com.projectardor.assistant.execution.web;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.projectardor.assistant.execution.service.AgentModelCatalogService;
import com.projectardor.assistant.execution.service.AgentModelDescriptionService;
import com.projectardor.platform.identity.auth.security.ArdorPrincipal;

@RestController
@RequestMapping("/api/agent/models")
public class AgentModelCatalogController {

    private final AgentModelCatalogService catalog;
    private final AgentModelDescriptionService descriptions;

    public AgentModelCatalogController(AgentModelCatalogService catalog, AgentModelDescriptionService descriptions) {
        this.catalog = catalog;
        this.descriptions = descriptions;
    }

    @GetMapping
    public List<AgentModelCatalogService.Option> options(@AuthenticationPrincipal ArdorPrincipal principal) {
        return catalog.options(principal.userId());
    }

    @PostMapping("/description")
    public DescriptionResponse description(@AuthenticationPrincipal ArdorPrincipal principal,
            @RequestBody DescriptionRequest request) {
        String fingerprint = catalog.descriptionFingerprint(principal.userId(), request.model());
        return new DescriptionResponse(
                descriptions.describe(principal.userId(), request.model(), request.locale(), fingerprint));
    }

    @PutMapping("/settings")
    public List<AgentModelCatalogService.Option> updateSettings(@AuthenticationPrincipal ArdorPrincipal principal,
            @RequestBody SettingsBatchRequest request) {
        return catalog.updateSettings(principal.userId(),
                request.settings() == null ? List.of() : request.settings());
    }

    public record DescriptionRequest(String model, String locale) {}
    public record DescriptionResponse(String description) {}
    public record SettingsBatchRequest(List<AgentModelCatalogService.SettingsUpdate> settings) {}
}
