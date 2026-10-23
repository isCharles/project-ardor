package com.projectardor.assistant.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import com.projectardor.assistant.tools.modules.AgentToolProvider;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.internal.JsonSchemaElementJsonUtils;

/** Schema-only discovery; never binds a user, executes a tool or grants permission. */
@Service
public class AgentToolSchemaCatalog {
    private final List<AgentToolProvider> providers;

    public AgentToolSchemaCatalog(List<AgentToolProvider> providers) {
        this.providers = List.copyOf(providers);
    }

    public Map<String, Object> schemas() {
        var types = new java.util.ArrayList<Class<?>>();
        types.add(CareerAgentTools.BoundCareerTools.class);
        providers.forEach(provider -> types.addAll(provider.toolTypes()));
        Map<String, Object> result = new LinkedHashMap<>();
        for (Class<?> type : types) {
            for (var spec : ToolSpecifications.toolSpecificationsFrom(type)) {
                // Pinned-library conversion stays in the assistant adapter; preserve optional,
                // nested and enum parameters exactly as execution does.
                var schema = new LinkedHashMap<String, Object>(spec.parameters() == null
                        ? Map.of("type", "object", "properties", Map.of())
                        : JsonSchemaElementJsonUtils.toMap(spec.parameters()));
                if (spec.description() != null) schema.put("description", spec.description());
                if (result.putIfAbsent(spec.name(), schema) != null) {
                    throw new IllegalStateException("工具重复登记：" + spec.name());
                }
            }
        }
        return result;
    }
}

