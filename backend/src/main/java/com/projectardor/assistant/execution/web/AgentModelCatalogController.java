package com.projectardor.assistant.execution.web;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.projectardor.assistant.execution.service.AgentModelCatalogService;
import com.projectardor.platform.identity.auth.security.ArdorPrincipal;

@RestController
@RequestMapping("/api/agent/models")
public class AgentModelCatalogController {

    private final AgentModelCatalogService catalog;

    public AgentModelCatalogController(AgentModelCatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public List<AgentModelCatalogService.Option> options(@AuthenticationPrincipal ArdorPrincipal principal) {
        return catalog.options(principal.userId());
    }
}
