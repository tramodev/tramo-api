// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.project.service;

import com.tramo.backend.project.dto.GraphPreviewDTO;
import com.tramo.backend.trail.dto.AssociationDTO;
import com.tramo.backend.trail.entity.Association;
import com.tramo.backend.trail.entity.Item;
import com.tramo.backend.trail.entity.Trail;
import com.tramo.backend.trail.entity.TrailItem;
import com.tramo.backend.trail.repository.AssociationRepository;
import com.tramo.backend.trail.repository.TrailItemRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

record GraphLookup(Map<Long, List<TrailItem>> membershipsByTrailId, Map<Long, List<Association>> outgoingByItemId) {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    static GraphLookup forTrailIds(List<Long> trailIds, TrailItemRepository trailItemRepository,
                                    AssociationRepository itemLinkRepository) {
        if (trailIds.isEmpty()) return new GraphLookup(Map.of(), Map.of());
        Map<Long, List<TrailItem>> membershipsByTrailId = trailItemRepository
                .findByTrailIdInWithItemContent(trailIds).stream()
                .collect(Collectors.groupingBy(ti -> ti.getTrail().getId(), LinkedHashMap::new, Collectors.toList()));
        Set<Long> itemIds = membershipsByTrailId.values().stream().flatMap(List::stream)
                .map(ti -> ti.getItem().getId()).collect(Collectors.toSet());
        Map<Long, List<Association>> outgoingByItemId = itemIds.isEmpty() ? Map.of()
                : itemLinkRepository.findBySourceItemIdIn(itemIds).stream()
                        .collect(Collectors.groupingBy(a -> a.getSourceItem().getId()));
        return new GraphLookup(membershipsByTrailId, outgoingByItemId);
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
        Set<String> pairs = new HashSet<>();
        outgoingByItemId.values().stream().flatMap(List::stream)
                .filter(a -> itemById.containsKey(a.getSourceItem().getId()) && itemById.containsKey(a.getTargetId()))
                .forEach(a -> pairs.add(pair(a.getSourceItem().getId(), a.getTargetId())));
        List<GraphPreviewDTO.GraphItemDTO> items = itemById.values().stream().map(item -> {
            List<AssociationDTO> associations = new ArrayList<>(outgoingByItemId.getOrDefault(item.getId(), List.of()).stream()
                    .filter(a -> itemById.containsKey(a.getTargetId()))
                    .map(a -> new AssociationDTO(String.valueOf(a.getId()), String.valueOf(a.getTargetId()),
                            itemById.get(a.getTargetId()).getTitle(), a.getText()))
                    .toList());
            String content = item.getContent() == null ? null : item.getContent().getContent();
            for (Long targetId : linkedItemIds(content)) {
                String pair = pair(item.getId(), targetId);
                if (item.getId().equals(targetId) || !itemById.containsKey(targetId) || !pairs.add(pair)) continue;
                associations.add(new AssociationDTO("reference:" + pair, String.valueOf(targetId), itemById.get(targetId).getTitle(), null));
            }
            return new GraphPreviewDTO.GraphItemDTO(String.valueOf(item.getId()), item.getTitle(), associations);
        }).toList();
        return new GraphPreviewDTO(previewTrails, items);
    }

    private static String pair(Long a, Long b) {
        return Math.min(a, b) + ":" + Math.max(a, b);
    }

    private static Set<Long> linkedItemIds(String content) {
        if (content == null || content.isBlank()) return Set.of();
        try {
            Set<Long> ids = new HashSet<>();
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
