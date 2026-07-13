package com.mypath.backend.project.controller;

import com.mypath.backend.project.dto.ProfileBundleDTO;
import com.mypath.backend.project.dto.UpdateProfileRequestDTO;
import com.mypath.backend.project.dto.UserProfileDTO;
import com.mypath.backend.project.service.ProjectService;
import com.mypath.backend.user.entity.User;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    @PutMapping("/me")
    public ResponseEntity<UserProfileDTO> updateProfile(@AuthenticationPrincipal User user,
                                                          @RequestBody UpdateProfileRequestDTO request) {
        return ResponseEntity.ok(projectService.updateProfile(user, request));
    }

    // Replaces what used to be 6 separate GETs (stats/badges/bookmarks/upvoted/
    // forks/activity) — each paid its own JWT-auth user lookup and badges
    // recomputed stats independently; this is one round trip, one auth lookup,
    // stats computed once and reused for badges.
    @GetMapping("/bundle")
    public ResponseEntity<ProfileBundleDTO> getBundle(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(projectService.getProfileBundle(user));
    }
}
