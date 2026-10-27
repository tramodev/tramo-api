// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.moderation.controller;

import com.tramo.backend.common.ProjectIdCodec;
import com.tramo.backend.moderation.dto.AdminUserDTO;
import com.tramo.backend.moderation.dto.ModerationActionRequestDTO;
import com.tramo.backend.moderation.dto.ReportDTO;
import com.tramo.backend.moderation.dto.ReportFilterType;
import com.tramo.backend.moderation.service.ModerationService;
import com.tramo.backend.user.entity.User;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import jakarta.validation.Valid;
import com.tramo.backend.project.dto.PageResponseDTO;
import com.tramo.backend.moderation.dto.ModerationLogDTO;
import com.tramo.backend.moderation.entity.ReportStatus;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasAuthority('ADMIN')")
public class AdminController {
    private final ModerationService moderationService;
    private final ProjectIdCodec projectIdCodec;

    public AdminController(ModerationService moderationService, ProjectIdCodec projectIdCodec) {
        this.moderationService = moderationService;
        this.projectIdCodec = projectIdCodec;
    }

    @GetMapping("/reports")
    public ResponseEntity<PageResponseDTO<ReportDTO>> listReports(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @RequestParam(defaultValue = "ALL") ReportFilterType type,
            @RequestParam(defaultValue = "OPEN") ReportStatus status, @AuthenticationPrincipal User admin) {
        return ResponseEntity.ok(moderationService.listReports(admin, page, size, type, status));
    }

    @GetMapping("/moderation-log")
    public ResponseEntity<PageResponseDTO<ModerationLogDTO>> listLog(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @AuthenticationPrincipal User admin) {
        return ResponseEntity.ok(moderationService.listLog(admin, page, size));
    }

    @PostMapping("/projects/{id}/allow-publishing")
    public ResponseEntity<Void> allowPublishing(@PathVariable String id, @AuthenticationPrincipal User admin) {
        moderationService.allowPublishing(projectIdCodec.decode(id), admin);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/reports/{id}/dismiss")
    public ResponseEntity<Void> dismissReport(@PathVariable Long id,
                                                @RequestParam String type,
                                                @AuthenticationPrincipal User admin) {
        moderationService.dismissReport(id, type, admin);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/comment-reports/{id}/uphold")
    public ResponseEntity<Void> upholdCommentReport(@PathVariable Long id, @Valid @RequestBody(required = false) ModerationActionRequestDTO request,
                                                      @AuthenticationPrincipal User admin) {
        moderationService.upholdCommentReport(id, admin, request != null ? request.getReason() : null);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/users")
    public ResponseEntity<List<AdminUserDTO>> searchUsers(@RequestParam(required = false) String q, @AuthenticationPrincipal User admin) {
        return ResponseEntity.ok(moderationService.searchUsers(q, admin));
    }

    @PostMapping("/users/{id}/ban")
    public ResponseEntity<Void> banUser(@PathVariable Long id, @Valid @RequestBody(required = false) ModerationActionRequestDTO request,
                                         @AuthenticationPrincipal User admin) {
        moderationService.banUser(id, admin, request != null ? request.getReason() : null);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/users/{id}/unban")
    public ResponseEntity<Void> unbanUser(@PathVariable Long id, @AuthenticationPrincipal User admin) {
        moderationService.unbanUser(id, admin);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/projects/{id}/unpublish")
    public ResponseEntity<Void> unpublishProject(@PathVariable String id, @Valid @RequestBody(required = false) ModerationActionRequestDTO request,
                                                   @AuthenticationPrincipal User admin) {
        moderationService.unpublishProject(projectIdCodec.decode(id), admin, request != null ? request.getReason() : null);
        return ResponseEntity.ok().build();
    }
}
