package com.projectardor.assistant.tools;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * One stable JSON-Schema-like view of the tools exposed to the model.
 *
 * <p>This is intentionally derived from the same annotations LangChain4j uses, so the
 * developer trace can show the actual tool contract without a second hand-written schema.</p>
 */
@Service
public class AgentToolSchemaCatalog {

    public Map<String, Object> schemas() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Method method : CareerAgentTools.BoundCareerTools.class.getMethods()) {
            Tool tool = method.getAnnotation(Tool.class);
            if (tool == null) continue;
            Map<String, Object> properties = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();
            for (Parameter parameter : method.getParameters()) {
                String name = parameter.getName();
                P hint = parameter.getAnnotation(P.class);
                Map<String, Object> property = new LinkedHashMap<>();
                property.put("type", jsonType(parameter.getType()));
                if (hint != null && hint.value() != null && !hint.value().isBlank()) {
                    property.put("description", hint.value());
                }
                if (parameter.getType() == UUID.class) property.put("format", "uuid");
                if (parameter.getType() == Instant.class) property.put("format", "date-time");
                properties.put(name, property);
                required.add(name);
            }
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("description", String.join(" ", tool.value()));
            schema.put("properties", properties);
            schema.put("required", required);
            result.put(tool.name(), schema);
        }
        return result;
    }

    private static String jsonType(Class<?> type) {
        if (type == String.class || type == UUID.class || type == Instant.class || type.isEnum()) return "string";
        if (type == int.class || type == Integer.class || type == long.class || type == Long.class) return "integer";
        if (type == double.class || type == Double.class || type == float.class || type == Float.class) return "number";
        if (type == boolean.class || type == Boolean.class) return "boolean";
        if (List.class.isAssignableFrom(type) || Iterable.class.isAssignableFrom(type)) return "array";
        if (Map.class.isAssignableFrom(type)) return "object";
        return "object";
    }

    @SuppressWarnings("unused")
    private static String elementType(Type type) {
        if (type instanceof ParameterizedType parameterized && parameterized.getActualTypeArguments().length == 1) {
            Type element = parameterized.getActualTypeArguments()[0];
            return element instanceof Class<?> cls ? jsonType(cls) : "object";
        }
        return "object";
    }
}

