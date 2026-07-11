package com.mypath.backend.path.controller;

import com.mypath.backend.path.dto.PathRequestDTO;
import com.mypath.backend.path.dto.PathResponseDTO;
import com.mypath.backend.path.service.PathService;
import com.mypath.backend.user.entity.User;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class PathController {
    private final PathService pathService;

    public PathController(PathService pathService) {
        this.pathService = pathService;
    }

    @PostMapping("/project/{projectId}/path")
    public ResponseEntity<PathResponseDTO> create(@PathVariable Long projectId, @Valid @RequestBody PathRequestDTO request,
                                                    @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(pathService.create(projectId, request, user));
    }

    @GetMapping("/project/{projectId}/path")
    public ResponseEntity<List<PathResponseDTO>> getAllForProject(@PathVariable Long projectId, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(pathService.getAllForProject(projectId, user));
    }

    @GetMapping("/path/{id}")
    public ResponseEntity<PathResponseDTO> getById(@PathVariable Long id, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(pathService.getById(id, user));
    }

    @PutMapping("/path/{id}")
    public ResponseEntity<PathResponseDTO> update(@PathVariable Long id, @Valid @RequestBody PathRequestDTO request,
                                                    @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(pathService.update(id, request, user));
    }

    @DeleteMapping("/path/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, @AuthenticationPrincipal User user) {
        pathService.delete(id, user);
        return ResponseEntity.noContent().build();
    }
}
