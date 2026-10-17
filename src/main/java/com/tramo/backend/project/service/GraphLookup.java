package com.tramo.backend.project.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashSet;
import java.util.Set;

final class GraphLookup {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static Set<Long> linkedItemIds(String content) {
        if (content == null || content.isBlank()) return Set.of();
        try {
            Set<Long> ids = new LinkedHashSet<>();
            collectLinks(MAPPER.readTree(content).path("root"), ids);
            return ids;
        } catch (RuntimeException ignored) {
            return Set.of();
        }
    }

    private static void collectLinks(JsonNode node, Set<Long> ids) {
        if ("link".equals(node.path("type").asText(""))) {
            String rel = node.path("rel").asText("");
            if (rel.startsWith("tramo-idea:") || rel.startsWith("mypath-idea:")) {
                try { ids.add(Long.parseLong(rel.substring(rel.indexOf(':') + 1))); }
                catch (NumberFormatException ignored) { }
            }
        }
        for (JsonNode child : node.path("children")) collectLinks(child, ids);
    }
}
