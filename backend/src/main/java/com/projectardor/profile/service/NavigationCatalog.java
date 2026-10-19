package com.projectardor.profile.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Destination identity is a UI contract, never a ToolModule or permission grant. */
public final class NavigationCatalog {
    private final Map<String, Destination> destinations = new LinkedHashMap<>();
    private final List<String> defaults;

    public NavigationCatalog(ObjectMapper mapper) {
        try (var input = new ClassPathResource("navigation-catalog.json").getInputStream()) {
            JsonNode root = mapper.readTree(input);
            for (JsonNode node : root.path("destinations")) {
                var item = new Destination(node.path("id").asText(), node.path("legacyHref").asText(),
                        node.path("legacySelectable").asBoolean(true));
                if (!item.id().matches("[a-z][a-z0-9.-]*") || !item.legacyHref().startsWith("/app")
                        || destinations.putIfAbsent(item.id(), item) != null) {
                    throw new IllegalArgumentException("Invalid navigation catalog");
                }
            }
            defaults = normalizeIds(strings(root.path("defaults")));
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Navigation catalog could not be loaded", exception);
        }
    }

    public List<String> defaults() { return defaults; }

    public List<String> normalizeIds(List<String> ids) {
        if (ids == null || ids.size() > destinations.size()
                || ids.stream().anyMatch(id -> id == null || !destinations.containsKey(id))) {
            throw new IllegalArgumentException("侧栏入口无效");
        }
        var unique = new LinkedHashSet<String>();
        unique.add("chat");
        unique.addAll(ids);
        return List.copyOf(unique);
    }

    public List<String> legacyHrefs(List<String> ids) {
        return normalizeIds(ids).stream().map(id -> destinations.get(id).legacyHref()).distinct().toList();
    }

    public List<String> fromLegacy(List<String> hrefs, List<String> preservedIds) {
        if (hrefs == null || hrefs.size() > destinations.size()
                || hrefs.stream().anyMatch(href -> href == null
                    || destinations.values().stream().noneMatch(item -> item.legacyHref().equals(href)))) {
            throw new IllegalArgumentException("侧栏模块无效");
        }
        var ids = new ArrayList<String>();
        for (String href : hrefs) {
            // Old clients cannot distinguish a domain from a pinned child sharing its
            // compatibility URL. Retain that selection rather than silently flatten it.
            var existing = preservedIds.stream().filter(destinations::containsKey)
                    .filter(id -> destinations.get(id).legacyHref().equals(href)).toList();
            if (!existing.isEmpty()) ids.addAll(existing);
            else destinations.values().stream()
                    .filter(item -> item.legacySelectable() && item.legacyHref().equals(href))
                    .findFirst().ifPresent(item -> ids.add(item.id()));
        }
        return normalizeIds(ids.stream().distinct().toList());
    }

    public static List<String> strings(JsonNode array) {
        if (!array.isArray()) throw new IllegalArgumentException("侧栏配置格式无效");
        var values = new ArrayList<String>();
        for (JsonNode node : array) {
            if (!node.isTextual()) throw new IllegalArgumentException("侧栏配置格式无效");
            values.add(node.asText());
        }
        return List.copyOf(values);
    }

    private record Destination(String id, String legacyHref, boolean legacySelectable) {}
}
