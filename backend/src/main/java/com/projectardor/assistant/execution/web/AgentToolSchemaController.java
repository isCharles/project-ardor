package com.projectardor.assistant.execution.web;

import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.projectardor.assistant.tools.AgentToolSchemaCatalog;
import com.projectardor.platform.identity.auth.security.ArdorPrincipal;

/** Read-only tool contract used by the developer trace Schema tab. */
@RestController
@RequestMapping("/api/agent/tools")
public class AgentToolSchemaController {
    private final AgentToolSchemaCatalog schemas;

    public AgentToolSchemaController(AgentToolSchemaCatalog schemas) {
        this.schemas = schemas;
    }

    @GetMapping("/schemas")
    public Map<String, Object> schemas(@AuthenticationPrincipal ArdorPrincipal principal) {
        return schemas.schemas();
    }
}
