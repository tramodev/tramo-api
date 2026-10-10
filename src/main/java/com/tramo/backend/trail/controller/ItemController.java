// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.trail.controller;

import com.tramo.backend.trail.dto.ProjectTextStatsDTO;
import com.tramo.backend.common.ProjectIdCodec;
import com.tramo.backend.trail.dto.ItemContentRequestDTO;
import com.tramo.backend.trail.dto.ItemContentResponseDTO;
import com.tramo.backend.trail.dto.ItemRequestDTO;
import com.tramo.backend.trail.dto.ItemResponseDTO;
import com.tramo.backend.trail.dto.MapItemPreviewDTO;
import com.tramo.backend.trail.dto.TrailItemContentDTO;
import com.tramo.backend.trail.dto.TrailOrderRequestDTO;
import com.tramo.backend.trail.dto.TrailItemDTO;
import com.tramo.backend.trail.service.ItemService;
import com.tramo.backend.user.entity.User;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ItemController {
    private final ItemService itemService;
    private final ProjectIdCodec projectIdCodec;

    public ItemController(ItemService itemService, ProjectIdCodec projectIdCodec) {
        this.itemService = itemService;
        this.projectIdCodec = projectIdCodec;
    }

    @PostMapping("/trail/{trailId}/item")
    public ResponseEntity<ItemResponseDTO> create(@PathVariable Long trailId, @Valid @RequestBody ItemRequestDTO request,
                                                    @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(itemService.create(trailId, request, user));
    }

    
    @PostMapping("/project/{projectId}/item")
    public ResponseEntity<ItemResponseDTO> createLoose(@PathVariable String projectId, @Valid @RequestBody ItemRequestDTO request,
                                                       @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(itemService.createLoose(projectIdCodec.decode(projectId), request, user));
    }

    @GetMapping("/project/{projectId}/item")
    public ResponseEntity<List<ItemResponseDTO>> getItemsForProject(@PathVariable String projectId, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(itemService.getItemsForProject(projectIdCodec.decode(projectId), user));
    }

    @GetMapping("/project/{projectId}/map-preview")
    public ResponseEntity<Map<String, MapItemPreviewDTO>> getMapPreviews(@PathVariable String projectId,
                                                                          @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(itemService.getMapPreviews(projectIdCodec.decode(projectId), user));
    }

    @GetMapping("/project/{projectId}/text-stats")
    public ResponseEntity<ProjectTextStatsDTO> getProjectTextStats(
            @PathVariable String projectId, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(itemService.getProjectTextStats(projectIdCodec.decode(projectId), user));
    }

    @GetMapping("/project/{projectId}/item/search")
    public ResponseEntity<List<Long>> searchItems(@PathVariable String projectId,
                                                  @RequestParam(required = false) String q,
                                                  @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(itemService.searchItemIds(projectIdCodec.decode(projectId), q, user));
    }

    @PutMapping("/trail/{trailId}/item/order")
    public ResponseEntity<Void> reorderTrailItems(@PathVariable Long trailId,
                                                  @Valid @RequestBody TrailOrderRequestDTO request,
                                                  @AuthenticationPrincipal User user) {
        itemService.reorderTrailItems(trailId, request.itemIds(), user);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/trail/{trailId}/content")
    public ResponseEntity<List<TrailItemContentDTO>> getContentsForTrail(@PathVariable Long trailId,
                                                                        @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(itemService.getContentsForTrail(trailId, user));
    }

    @GetMapping("/trail/{trailId}/item")
    public ResponseEntity<List<TrailItemDTO>> getAllForTrail(@PathVariable Long trailId, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(itemService.getAllForTrail(trailId, user));
    }

    @PutMapping("/item/{id}")
    public ResponseEntity<ItemResponseDTO> update(@PathVariable Long id, @Valid @RequestBody ItemRequestDTO request,
                                                    @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(itemService.update(id, request, user));
    }

    @DeleteMapping("/item/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, @AuthenticationPrincipal User user) {
        itemService.delete(id, user);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/item/{id}/content")
    public ResponseEntity<ItemContentResponseDTO> getContent(@PathVariable Long id, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(itemService.getContent(id, user));
    }

    @PutMapping("/item/{id}/content")
    public ResponseEntity<Void> updateContent(@PathVariable Long id, @RequestBody ItemContentRequestDTO request,
                                               @AuthenticationPrincipal User user) {
        itemService.updateContent(id, request.getContent(), request.getExtractionEpoch(), request.getExpectedContent(), user);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/trail/{trailId}/item/{itemId}")
    public ResponseEntity<Void> attach(@PathVariable Long trailId, @PathVariable Long itemId, @AuthenticationPrincipal User user) {
        itemService.attachToTrail(trailId, itemId, user);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/trail/{trailId}/item/{itemId}")
    public ResponseEntity<Void> detach(@PathVariable Long trailId, @PathVariable Long itemId, @AuthenticationPrincipal User user) {
        itemService.detachFromTrail(trailId, itemId, user);
        return ResponseEntity.noContent().build();
    }

}
