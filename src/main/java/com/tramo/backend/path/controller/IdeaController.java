package com.tramo.backend.path.controller;

import com.tramo.backend.path.dto.IdeaContentRequestDTO;
import com.tramo.backend.path.dto.IdeaContentResponseDTO;
import com.tramo.backend.path.dto.IdeaRequestDTO;
import com.tramo.backend.path.dto.IdeaResponseDTO;
import com.tramo.backend.path.service.IdeaService;
import com.tramo.backend.user.entity.User;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class IdeaController {
    private final IdeaService ideaService;

    public IdeaController(IdeaService ideaService) {
        this.ideaService = ideaService;
    }

    @PostMapping("/path/{pathId}/idea")
    public ResponseEntity<IdeaResponseDTO> create(@PathVariable Long pathId, @Valid @RequestBody IdeaRequestDTO request,
                                                    @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ideaService.create(pathId, request, user));
    }

    @GetMapping("/path/{pathId}/idea")
    public ResponseEntity<List<IdeaResponseDTO>> getAllForPath(@PathVariable Long pathId, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ideaService.getAllForPath(pathId, user));
    }

    @PutMapping("/idea/{id}")
    public ResponseEntity<IdeaResponseDTO> update(@PathVariable Long id, @Valid @RequestBody IdeaRequestDTO request,
                                                    @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ideaService.update(id, request, user));
    }

    @DeleteMapping("/idea/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, @AuthenticationPrincipal User user) {
        ideaService.delete(id, user);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/idea/{id}/content")
    public ResponseEntity<IdeaContentResponseDTO> getContent(@PathVariable Long id, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ideaService.getContent(id, user));
    }

    @PutMapping("/idea/{id}/content")
    public ResponseEntity<Void> updateContent(@PathVariable Long id, @RequestBody IdeaContentRequestDTO request,
                                               @AuthenticationPrincipal User user) {
        ideaService.updateContent(id, request.getContent(), user);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/path/{pathId}/idea/{ideaId}")
    public ResponseEntity<Void> attach(@PathVariable Long pathId, @PathVariable Long ideaId, @AuthenticationPrincipal User user) {
        ideaService.attachToPath(pathId, ideaId, user);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/path/{pathId}/idea/{ideaId}")
    public ResponseEntity<Void> detach(@PathVariable Long pathId, @PathVariable Long ideaId, @AuthenticationPrincipal User user) {
        ideaService.detachFromPath(pathId, ideaId, user);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/idea/{id}/link/{targetId}")
    public ResponseEntity<Void> link(@PathVariable Long id, @PathVariable Long targetId, @AuthenticationPrincipal User user) {
        ideaService.linkIdeas(id, targetId, user);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/idea/{id}/link/{targetId}")
    public ResponseEntity<Void> unlink(@PathVariable Long id, @PathVariable Long targetId, @AuthenticationPrincipal User user) {
        ideaService.unlinkIdeas(id, targetId, user);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/idea/{id}/link")
    public ResponseEntity<List<IdeaResponseDTO>> getLinks(@PathVariable Long id, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ideaService.getLinkedIdeas(id, user));
    }
}
