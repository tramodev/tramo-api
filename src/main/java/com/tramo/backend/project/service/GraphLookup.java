package com.tramo.backend.project.service;

import com.tramo.backend.project.dto.GraphPreviewDTO;
import com.tramo.backend.trail.entity.Item;
import com.tramo.backend.trail.entity.Trail;
import com.tramo.backend.trail.entity.TrailItem;
import com.tramo.backend.trail.repository.TrailItemRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

record GraphLookup(Map<Long, List<TrailItem>> membershipsByTrailId) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static GraphLookup forTrailIds(List<Long> trailIds, TrailItemRepository trailItemRepository) {
        if (trailIds.isEmpty()) return new GraphLookup(Map.of());
        return new GraphLookup(trailItemRepository.findByTrailIdInWithItemContent(trailIds).stream()
                .collect(Collectors.groupingBy(ti -> ti.getTrail().getId(), LinkedHashMap::new, Collectors.toList())));
    }

    GraphPreviewDTO buildGraphPreview(Trail trail) {
        if (membershipsByTrailId.getOrDefault(trail.getId(), List.of()).isEmpty()) return null;
        return buildGraphPreview(List.of(trail));
    }

    GraphPreviewDTO buildGraphPreview(List<Trail> trails) {
        if (trails.isEmpty()) return null;
        Map<Long, Item> itemById = trails.stream()
                .flatMap(trail -> membershipsByTrailId.getOrDefault(trail.getId(), List.of()).stream())
                .collect(Collectors.toMap(m -> m.getItem().getId(), TrailItem::getItem, (a, b) -> a, LinkedHashMap::new));
        List<GraphPreviewDTO.GraphTrailDTO> previewTrails = trails.stream()
                .map(trail -> new GraphPreviewDTO.GraphTrailDTO(String.valueOf(trail.getId()), trail.getTitle(),
                        membershipsByTrailId.getOrDefault(trail.getId(), List.of()).stream()
                                .map(m -> String.valueOf(m.getItem().getId())).toList()))
                .toList();
        List<GraphPreviewDTO.GraphItemDTO> items = itemById.values().stream().map(item -> {
            String content = item.getContent() == null ? null : item.getContent().getContent();
            List<String> linkedIds = linkedItemIds(content).stream()
                    .filter(id -> !id.equals(item.getId()) && itemById.containsKey(id))
                    .map(String::valueOf).toList();
            return new GraphPreviewDTO.GraphItemDTO(String.valueOf(item.getId()), item.getTitle(), linkedIds);
        }).toList();
        return new GraphPreviewDTO(previewTrails, items);
    }

    private static Set<Long> linkedItemIds(String content) {
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
