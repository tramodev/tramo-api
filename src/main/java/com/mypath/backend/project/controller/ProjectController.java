package com.mypath.backend.project.controller;

import com.mypath.backend.moderation.dto.ReportRequestDTO;
import com.mypath.backend.moderation.service.ModerationService;
import com.mypath.backend.project.dto.BookmarkResponseDTO;
import com.mypath.backend.project.dto.ProjectRequestDTO;
import com.mypath.backend.project.dto.ProjectResponseDTO;
import com.mypath.backend.project.dto.VoteResponseDTO;
import com.mypath.backend.project.service.ProjectService;
import com.mypath.backend.user.entity.User;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/project")
public class ProjectController {
    private final ProjectService projectService;
    private final ModerationService moderationService;

    public ProjectController(ProjectService projectService, ModerationService moderationService) {
        this.projectService = projectService;
        this.moderationService = moderationService;
    }

    @PostMapping
    public ResponseEntity<ProjectResponseDTO> create(@Valid @RequestBody ProjectRequestDTO request,
                                                       @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(projectService.create(request, user));
    }

    @GetMapping
    public ResponseEntity<List<ProjectResponseDTO>> getAll(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(projectService.getAllForUser(user));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProjectResponseDTO> getById(@PathVariable Long id, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(projectService.getById(id, user));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ProjectResponseDTO> update(@PathVariable Long id, @Valid @RequestBody ProjectRequestDTO request,
                                                       @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(projectService.update(id, request, user));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, @AuthenticationPrincipal User user) {
        projectService.delete(id, user);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/vote")
    public ResponseEntity<VoteResponseDTO> toggleVote(@PathVariable Long id, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(projectService.toggleVote(id, user));
    }

    @PostMapping("/{id}/fork")
    public ResponseEntity<ProjectResponseDTO> fork(@PathVariable Long id, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(projectService.fork(id, user));
    }

    @PostMapping("/{id}/bookmark")
    public ResponseEntity<BookmarkResponseDTO> toggleBookmark(@PathVariable Long id, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(projectService.toggleBookmark(id, user));
    }

    @PostMapping("/{id}/report")
    public ResponseEntity<Void> report(@PathVariable Long id, @Valid @RequestBody ReportRequestDTO request,
                                        @AuthenticationPrincipal User user) {
        moderationService.submitReport(id, user, request.getReason());
        return ResponseEntity.ok().build();
    }
}
