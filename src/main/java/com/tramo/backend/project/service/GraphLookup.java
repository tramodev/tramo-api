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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

record GraphLookup(Map<Long, List<TrailItem>> membershipsByTrailId, Map<Long, List<Association>> outgoingByItemId) {
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
        List<GraphPreviewDTO.GraphItemDTO> items = itemById.values().stream()
                .map(item -> new GraphPreviewDTO.GraphItemDTO(
                        String.valueOf(item.getId()),
                        item.getTitle(),
                        outgoingByItemId.getOrDefault(item.getId(), List.of()).stream()
                                .filter(a -> itemById.containsKey(a.getTargetId()))
                                .map(a -> new AssociationDTO(String.valueOf(a.getId()), String.valueOf(a.getTargetId()),
                                        itemById.get(a.getTargetId()).getTitle(), a.getText()))
                                .toList()
                ))
                .toList();
        return new GraphPreviewDTO(previewTrails, items);
    }
}
