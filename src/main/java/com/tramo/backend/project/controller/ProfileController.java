package com.tramo.backend.project.controller;

import com.tramo.backend.project.dto.ActivityItemDTO;
import com.tramo.backend.project.dto.ForkFeedItemDTO;
import com.tramo.backend.project.dto.PageResponseDTO;
import com.tramo.backend.project.dto.ProfileStatsBundleDTO;
import com.tramo.backend.project.dto.ProjectFeedItemDTO;
import com.tramo.backend.project.dto.UpdateProfileRequestDTO;
import com.tramo.backend.project.dto.UserProfileDTO;
import com.tramo.backend.project.service.ProjectService;
import com.tramo.backend.user.entity.User;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    @GetMapping("/stats")
    public ResponseEntity<ProfileStatsBundleDTO> getStats(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(projectService.getProfileStatsBundle(user));
    }

    @GetMapping("/published")
    public ResponseEntity<PageResponseDTO<ProjectFeedItemDTO>> getPublished(@AuthenticationPrincipal User user,
                                                                              @RequestParam(defaultValue = "0") int page,
                                                                              @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(projectService.getPublishedPage(user, page, size));
    }

    @GetMapping("/bookmarks")
    public ResponseEntity<PageResponseDTO<ProjectFeedItemDTO>> getBookmarks(@AuthenticationPrincipal User user,
                                                                              @RequestParam(defaultValue = "0") int page,
                                                                              @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(projectService.getBookmarksPage(user, page, size));
    }

    @GetMapping("/forks")
    public ResponseEntity<PageResponseDTO<ForkFeedItemDTO>> getForks(@AuthenticationPrincipal User user,
                                                                       @RequestParam(defaultValue = "0") int page,
                                                                       @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(projectService.getForksPage(user, page, size));
    }

    @GetMapping("/upvoted")
    public ResponseEntity<PageResponseDTO<ProjectFeedItemDTO>> getUpvoted(@AuthenticationPrincipal User user,
                                                                            @RequestParam(defaultValue = "0") int page,
                                                                            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(projectService.getUpvotedPage(user, page, size));
    }

    @GetMapping("/activity")
    public ResponseEntity<PageResponseDTO<ActivityItemDTO>> getActivity(@AuthenticationPrincipal User user,
                                                                          @RequestParam(defaultValue = "0") int page,
                                                                          @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(projectService.getActivityPage(user, page, size));
    }
}
