package com.mypath.backend.project.controller;

import com.mypath.backend.project.dto.ForkFeedItemDTO;
import com.mypath.backend.project.dto.ProfileStatsDTO;
import com.mypath.backend.project.dto.ProjectFeedItemDTO;
import com.mypath.backend.project.dto.UserProfileDTO;
import com.mypath.backend.project.service.ProjectService;
import com.mypath.backend.user.entity.User;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// Everything here is the signed-in user's own data — no path param, no
// visibility gating needed, since /api/profile/** requires authentication
// (SecurityConfiguration only permits /api/auth/** and /api/public/** anonymously).
@RestController
@RequestMapping("/api/profile")
public class ProfileController {
    private final ProjectService projectService;

    public ProfileController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @GetMapping("/me")
    public ResponseEntity<UserProfileDTO> getProfile(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(projectService.getProfile(user));
    }

    @GetMapping("/stats")
    public ResponseEntity<ProfileStatsDTO> getStats(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(projectService.getProfileStats(user));
    }

    @GetMapping("/bookmarks")
    public ResponseEntity<List<ProjectFeedItemDTO>> getBookmarks(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(projectService.getMyBookmarks(user));
    }

    @GetMapping("/upvoted")
    public ResponseEntity<List<ProjectFeedItemDTO>> getUpvoted(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(projectService.getMyUpvoted(user));
    }

    @GetMapping("/forks")
    public ResponseEntity<List<ForkFeedItemDTO>> getForks(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(projectService.getMyForks(user));
    }
}
