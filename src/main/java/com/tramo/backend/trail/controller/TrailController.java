package com.tramo.backend.trail.controller;

import com.tramo.backend.common.ProjectIdCodec;
import com.tramo.backend.trail.dto.TrailRequestDTO;
import com.tramo.backend.trail.dto.TrailResponseDTO;
import com.tramo.backend.trail.service.TrailService;
import com.tramo.backend.user.entity.User;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class TrailController {
    private final TrailService trailService;
    private final ProjectIdCodec projectIdCodec;

    public TrailController(TrailService trailService, ProjectIdCodec projectIdCodec) {
        this.trailService = trailService;
        this.projectIdCodec = projectIdCodec;
    }

    @PostMapping("/project/{projectId}/trail")
    public ResponseEntity<TrailResponseDTO> create(@PathVariable String projectId, @Valid @RequestBody TrailRequestDTO request,
                                                    @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(trailService.create(projectIdCodec.decode(projectId), request, user));
    }

    @GetMapping("/project/{projectId}/trail")
    public ResponseEntity<List<TrailResponseDTO>> getAllForProject(@PathVariable String projectId, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(trailService.getAllForProject(projectIdCodec.decode(projectId), user));
    }

    @GetMapping("/trail/{id}")
    public ResponseEntity<TrailResponseDTO> getById(@PathVariable Long id, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(trailService.getById(id, user));
    }

    @PutMapping("/trail/{id}")
    public ResponseEntity<TrailResponseDTO> update(@PathVariable Long id, @Valid @RequestBody TrailRequestDTO request,
                                                    @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(trailService.update(id, request, user));
    }

    @DeleteMapping("/trail/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, @AuthenticationPrincipal User user) {
        trailService.delete(id, user);
        return ResponseEntity.noContent().build();
    }
}
