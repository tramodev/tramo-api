package com.tramo.backend.trail.controller;

import com.tramo.backend.trail.dto.ItemContentRequestDTO;
import com.tramo.backend.trail.dto.ItemContentResponseDTO;
import com.tramo.backend.trail.dto.ItemRequestDTO;
import com.tramo.backend.trail.dto.ItemResponseDTO;
import com.tramo.backend.trail.service.ItemService;
import com.tramo.backend.user.entity.User;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class ItemController {
    private final ItemService itemService;

    public ItemController(ItemService itemService) {
        this.itemService = itemService;
    }

    @PostMapping("/trail/{trailId}/item")
    public ResponseEntity<ItemResponseDTO> create(@PathVariable Long trailId, @Valid @RequestBody ItemRequestDTO request,
                                                    @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(itemService.create(trailId, request, user));
    }

    @GetMapping("/trail/{trailId}/item")
    public ResponseEntity<List<ItemResponseDTO>> getAllForTrail(@PathVariable Long trailId, @AuthenticationPrincipal User user) {
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
        itemService.updateContent(id, request.getContent(), user);
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

    @PostMapping("/item/{id}/link/{targetId}")
    public ResponseEntity<Void> link(@PathVariable Long id, @PathVariable Long targetId, @AuthenticationPrincipal User user) {
        itemService.linkItems(id, targetId, user);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/item/{id}/link/{targetId}")
    public ResponseEntity<Void> unlink(@PathVariable Long id, @PathVariable Long targetId, @AuthenticationPrincipal User user) {
        itemService.unlinkItems(id, targetId, user);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/item/{id}/link")
    public ResponseEntity<List<ItemResponseDTO>> getLinks(@PathVariable Long id, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(itemService.getLinkedItems(id, user));
    }
}
