package com.tramo.backend.project.service;

import com.tramo.backend.project.dto.GraphPreviewDTO;
import com.tramo.backend.trail.dto.AssociationDTO;
import com.tramo.backend.trail.entity.Association;
import com.tramo.backend.trail.entity.AssociationTargetType;
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
                .findByTrailIdInWithItemContentAndAssociation(trailIds).stream()
                .collect(Collectors.groupingBy(ti -> ti.getTrail().getId(), LinkedHashMap::new, Collectors.toList()));
        Set<Long> itemIds = membershipsByTrailId.values().stream().flatMap(List::stream)
                .map(ti -> ti.getItem().getId()).collect(Collectors.toSet());
        Map<Long, List<Association>> outgoingByItemId = itemIds.isEmpty() ? Map.of()
                : itemLinkRepository.findBySourceItemIdIn(itemIds).stream()
                        .collect(Collectors.groupingBy(a -> a.getSourceItem().getId()));
        return new GraphLookup(membershipsByTrailId, outgoingByItemId);
    }

    GraphPreviewDTO buildGraphPreview(Trail trail) {
        List<TrailItem> memberships = membershipsByTrailId.getOrDefault(trail.getId(), List.of());
        if (memberships.isEmpty()) return null;
        Map<Long, Item> itemById = memberships.stream()
                .collect(Collectors.toMap(m -> m.getItem().getId(), TrailItem::getItem, (a, b) -> a, LinkedHashMap::new));
        List<String> itemIds = memberships.stream().map(m -> String.valueOf(m.getItem().getId())).toList();
        List<GraphPreviewDTO.GraphItemDTO> items = itemById.values().stream()
                .map(item -> new GraphPreviewDTO.GraphItemDTO(
                        String.valueOf(item.getId()),
                        item.getTitle(),
                        outgoingByItemId.getOrDefault(item.getId(), List.of()).stream()
                                .filter(a -> a.getTargetType() == AssociationTargetType.ITEM && itemById.containsKey(a.getTargetId()))
                                .map(a -> new AssociationDTO(String.valueOf(a.getId()), a.getType().name(),
                                        a.getTargetType().name(), String.valueOf(a.getTargetId()),
                                        itemById.get(a.getTargetId()).getTitle()))
                                .toList()
                ))
                .toList();
        return new GraphPreviewDTO(String.valueOf(trail.getId()), trail.getTitle(), itemIds, items);
    }
}
