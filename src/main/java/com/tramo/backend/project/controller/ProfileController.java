package com.tramo.backend.project.controller;

import com.tramo.backend.project.dto.ActivityItemDTO;
import com.tramo.backend.project.dto.ForkFeedItemDTO;
import com.tramo.backend.project.dto.PageResponseDTO;
import com.tramo.backend.project.dto.ProfileStatsBundleDTO;
import com.tramo.backend.project.dto.ProjectFeedItemDTO;
import com.tramo.backend.project.dto.UpdateProfileRequestDTO;
import com.tramo.backend.project.dto.UserProfileDTO;
import com.tramo.backend.project.service.ProfileFeedService;
import com.tramo.backend.project.service.ProfileService;
import com.tramo.backend.user.entity.User;
import jakarta.validation.Valid;
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
    private final ProfileService profileService;
    private final ProfileFeedService profileFeedService;

    public ProfileController(ProfileService profileService, ProfileFeedService profileFeedService) {
        this.profileService = profileService;
        this.profileFeedService = profileFeedService;
    }

    @GetMapping("/me")
    public ResponseEntity<UserProfileDTO> getProfile(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(profileService.getProfile(user));
    }

    @PutMapping("/me")
    public ResponseEntity<UserProfileDTO> updateProfile(@AuthenticationPrincipal User user,
                                                          @Valid @RequestBody UpdateProfileRequestDTO request) {
        return ResponseEntity.ok(profileService.updateProfile(user, request));
    }

    @GetMapping("/stats")
    public ResponseEntity<ProfileStatsBundleDTO> getStats(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(profileService.getProfileStatsBundle(user));
    }

    @GetMapping("/published")
    public ResponseEntity<PageResponseDTO<ProjectFeedItemDTO>> getPublished(@AuthenticationPrincipal User user,
                                                                              @RequestParam(defaultValue = "0") int page,
                                                                              @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(profileFeedService.getPublishedPage(user, page, size));
    }

    @GetMapping("/bookmarks")
    public ResponseEntity<PageResponseDTO<ProjectFeedItemDTO>> getBookmarks(@AuthenticationPrincipal User user,
                                                                              @RequestParam(defaultValue = "0") int page,
                                                                              @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(profileFeedService.getBookmarksPage(user, page, size));
    }

    @GetMapping("/forks")
    public ResponseEntity<PageResponseDTO<ForkFeedItemDTO>> getForks(@AuthenticationPrincipal User user,
                                                                       @RequestParam(defaultValue = "0") int page,
                                                                       @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(profileFeedService.getForksPage(user, page, size));
    }

    @GetMapping("/upvoted")
    public ResponseEntity<PageResponseDTO<ProjectFeedItemDTO>> getUpvoted(@AuthenticationPrincipal User user,
                                                                            @RequestParam(defaultValue = "0") int page,
                                                                            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(profileFeedService.getUpvotedPage(user, page, size));
    }

    @GetMapping("/activity")
    public ResponseEntity<PageResponseDTO<ActivityItemDTO>> getActivity(@AuthenticationPrincipal User user,
                                                                          @RequestParam(defaultValue = "0") int page,
                                                                          @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(profileFeedService.getActivityPage(user, page, size));
    }
}
