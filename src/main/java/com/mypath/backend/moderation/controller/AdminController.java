package com.mypath.backend.moderation.controller;

import com.mypath.backend.moderation.dto.AdminUserDTO;
import com.mypath.backend.moderation.dto.ModerationActionRequestDTO;
import com.mypath.backend.moderation.dto.ReportDTO;
import com.mypath.backend.moderation.service.ModerationService;
import com.mypath.backend.user.entity.User;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasAuthority('ADMIN')")
public class AdminController {
    private final ModerationService moderationService;

    public AdminController(ModerationService moderationService) {
        this.moderationService = moderationService;
    }

    @GetMapping("/reports")
    public ResponseEntity<List<ReportDTO>> listReports() {
        return ResponseEntity.ok(moderationService.listOpenReports());
    }

    @PostMapping("/reports/{id}/dismiss")
    public ResponseEntity<Void> dismissReport(@PathVariable Long id, @AuthenticationPrincipal User admin) {
        moderationService.dismissReport(id, admin);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/users")
    public ResponseEntity<List<AdminUserDTO>> searchUsers(@RequestParam(required = false) String q) {
        return ResponseEntity.ok(moderationService.searchUsers(q));
    }

    @PostMapping("/users/{id}/ban")
    public ResponseEntity<Void> banUser(@PathVariable Long id, @RequestBody(required = false) ModerationActionRequestDTO request,
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
    public ResponseEntity<Void> unpublishProject(@PathVariable Long id, @RequestBody(required = false) ModerationActionRequestDTO request,
                                                   @AuthenticationPrincipal User admin) {
        moderationService.unpublishProject(id, admin, request != null ? request.getReason() : null);
        return ResponseEntity.ok().build();
    }
}
