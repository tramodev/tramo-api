package com.tramo.backend.project.controller;

import com.tramo.backend.common.ProjectIdCodec;
import com.tramo.backend.moderation.dto.ReportRequestDTO;
import com.tramo.backend.moderation.service.ModerationService;
import com.tramo.backend.project.dto.BookmarkResponseDTO;
import com.tramo.backend.project.dto.ProjectRequestDTO;
import com.tramo.backend.project.dto.ProjectResponseDTO;
import com.tramo.backend.project.dto.VoteResponseDTO;
import com.tramo.backend.project.service.ProjectService;
import com.tramo.backend.security.ClientIp;
import com.tramo.backend.user.entity.User;
import jakarta.servlet.http.HttpServletRequest;
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
    private final ProjectIdCodec projectIdCodec;

    public ProjectController(ProjectService projectService, ModerationService moderationService, ProjectIdCodec projectIdCodec) {
        this.projectService = projectService;
        this.moderationService = moderationService;
        this.projectIdCodec = projectIdCodec;
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
    public ResponseEntity<ProjectResponseDTO> getById(@PathVariable String id, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(projectService.getById(projectIdCodec.decode(id), user));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ProjectResponseDTO> update(@PathVariable String id, @Valid @RequestBody ProjectRequestDTO request,
                                                       @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(projectService.update(projectIdCodec.decode(id), request, user));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id, @AuthenticationPrincipal User user) {
        projectService.delete(projectIdCodec.decode(id), user);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/vote")
    public ResponseEntity<VoteResponseDTO> toggleVote(@PathVariable String id, @AuthenticationPrincipal User user,
                                                      @RequestHeader(value = "X-Anon-Id", required = false) String anonId,
                                                      HttpServletRequest request) {
        return ResponseEntity.ok(projectService.toggleVote(projectIdCodec.decode(id), user, ClientIp.from(request), anonId));
    }

    @PostMapping("/{id}/fork")
    public ResponseEntity<ProjectResponseDTO> fork(@PathVariable String id, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(projectService.fork(projectIdCodec.decode(id), user));
    }

    @PostMapping("/{id}/bookmark")
    public ResponseEntity<BookmarkResponseDTO> toggleBookmark(@PathVariable String id, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(projectService.toggleBookmark(projectIdCodec.decode(id), user));
    }

    @PostMapping("/{id}/report")
    public ResponseEntity<Void> report(@PathVariable String id, @Valid @RequestBody ReportRequestDTO request,
                                        @AuthenticationPrincipal User user) {
        moderationService.submitReport(projectIdCodec.decode(id), user, request.getReason());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/share")
    public ResponseEntity<Void> share(@PathVariable String id, @AuthenticationPrincipal User user) {
        projectService.shareProject(projectIdCodec.decode(id), user);
        return ResponseEntity.ok().build();
    }
}
