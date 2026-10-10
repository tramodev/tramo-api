// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.project.controller;

import com.tramo.backend.common.ProjectIdCodec;
import com.tramo.backend.moderation.dto.ReportRequestDTO;
import com.tramo.backend.moderation.service.ModerationService;
import com.tramo.backend.project.dto.BookmarkResponseDTO;
import com.tramo.backend.project.dto.StartProjectRequest;
import com.tramo.backend.project.dto.StartedProjectDTO;
import com.tramo.backend.project.service.ProjectStartService;
import com.tramo.backend.project.dto.ProjectImageDTO;
import com.tramo.backend.project.dto.ProjectRequestDTO;
import com.tramo.backend.project.dto.ProjectResponseDTO;
import com.tramo.backend.project.dto.EditorBootstrapDTO;
import com.tramo.backend.project.dto.ProjectSnapshotDetailDTO;
import com.tramo.backend.project.dto.ProjectSnapshotSummaryDTO;
import com.tramo.backend.project.dto.SetThumbnailRequestDTO;
import com.tramo.backend.project.dto.VoteResponseDTO;
import com.tramo.backend.project.service.ProjectEngagementService;
import com.tramo.backend.project.service.ProjectForkService;
import com.tramo.backend.project.service.ProjectPublishService;
import com.tramo.backend.project.service.ProjectService;
import com.tramo.backend.project.service.ProjectEditorService;
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
    private final ProjectStartService startService;
    private final ProjectService projectService;
    private final ProjectEditorService projectEditorService;
    private final ProjectPublishService publishService;
    private final ProjectForkService forkService;
    private final ProjectEngagementService engagementService;
    private final ModerationService moderationService;
    private final ProjectIdCodec projectIdCodec;
    private final ClientIp clientIp;

    public ProjectController(ProjectStartService startService, ProjectService projectService, ProjectEditorService projectEditorService, ProjectPublishService publishService, ProjectForkService forkService, ProjectEngagementService engagementService, ModerationService moderationService, ProjectIdCodec projectIdCodec, ClientIp clientIp) {
        this.startService = startService;
        this.projectService = projectService;
        this.projectEditorService = projectEditorService;
        this.publishService = publishService;
        this.forkService = forkService;
        this.engagementService = engagementService;
        this.moderationService = moderationService;
        this.projectIdCodec = projectIdCodec;
        this.clientIp = clientIp;
    }

    @PostMapping
    public ResponseEntity<ProjectResponseDTO> create(@Valid @RequestBody ProjectRequestDTO request,
                                                       @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(projectService.create(request, user));
    }

    @PostMapping("/start")
    public ResponseEntity<StartedProjectDTO> start(
            @Valid @RequestBody StartProjectRequest request,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(startService.create(request, user));
    }

    @PostMapping("/example")
    public ResponseEntity<StartedProjectDTO> example(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(startService.createExample(user));
    }

    @PostMapping("/{id}/start")
    public ResponseEntity<StartedProjectDTO> startExisting(
            @PathVariable String id, @RequestParam(required = false) Long trailId, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(startService.start(projectIdCodec.decode(id), trailId, user));
    }

    @GetMapping
    public ResponseEntity<List<ProjectResponseDTO>> getAll(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(projectService.getAllForUser(user));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProjectResponseDTO> getById(@PathVariable String id, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(projectService.getById(projectIdCodec.decode(id), user));
    }

    @GetMapping("/{id}/editor")
    public ResponseEntity<EditorBootstrapDTO> getEditor(@PathVariable String id,
            @RequestParam(required = false) Long noteId, @RequestParam(required = false) Long trailId,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(projectEditorService.get(projectIdCodec.decode(id), noteId, trailId, user));
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
        return ResponseEntity.ok(engagementService.toggleVote(projectIdCodec.decode(id), user, clientIp.from(request), anonId));
    }

    @PostMapping("/{id}/publish")
    public ResponseEntity<ProjectResponseDTO> publish(@PathVariable String id, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(publishService.publish(projectIdCodec.decode(id), user));
    }

    @PutMapping("/{id}/thumbnail")
    public ResponseEntity<ProjectResponseDTO> setThumbnail(@PathVariable String id, @Valid @RequestBody SetThumbnailRequestDTO request,
                                                             @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(projectService.setThumbnail(projectIdCodec.decode(id), request, user));
    }

    @GetMapping("/{id}/images")
    public ResponseEntity<List<ProjectImageDTO>> listImages(@PathVariable String id, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(projectService.listProjectImages(projectIdCodec.decode(id), user));
    }

    @PostMapping("/{id}/fork")
    public ResponseEntity<ProjectResponseDTO> fork(@PathVariable String id, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(forkService.fork(projectIdCodec.decode(id), user));
    }

    @PostMapping("/{id}/bookmark")
    public ResponseEntity<BookmarkResponseDTO> toggleBookmark(@PathVariable String id, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(engagementService.toggleBookmark(projectIdCodec.decode(id), user));
    }

    @PostMapping("/{id}/report")
    public ResponseEntity<Void> report(@PathVariable String id, @Valid @RequestBody ReportRequestDTO request,
                                        @AuthenticationPrincipal User user) {
        moderationService.submitReport(projectIdCodec.decode(id), user, request.getReason());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/share")
    public ResponseEntity<Void> share(@PathVariable String id, @AuthenticationPrincipal User user) {
        publishService.shareProject(projectIdCodec.decode(id), user);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{id}/versions")
    public ResponseEntity<List<ProjectSnapshotSummaryDTO>> listVersions(@PathVariable String id, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(publishService.listSnapshots(projectIdCodec.decode(id), user));
    }

    @GetMapping("/{id}/versions/{snapshotId}")
    public ResponseEntity<ProjectSnapshotDetailDTO> getVersion(@PathVariable String id, @PathVariable Long snapshotId,
                                                                @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(publishService.getSnapshotDetail(projectIdCodec.decode(id), snapshotId, user));
    }
}
