package com.tramo.backend.project.controller;

import com.tramo.backend.common.ProjectIdCodec;
import com.tramo.backend.project.dto.ExploreBundleDTO;
import com.tramo.backend.project.dto.FollowUserDTO;
import com.tramo.backend.project.dto.PageResponseDTO;
import com.tramo.backend.project.dto.ProjectFeedItemDTO;
import com.tramo.backend.project.dto.ProjectSnapshotDetailDTO;
import com.tramo.backend.project.dto.PublicProfileDTO;
import com.tramo.backend.project.dto.PublicProjectResponseDTO;
import com.tramo.backend.project.dto.SitemapProjectDTO;
import com.tramo.backend.project.dto.SitemapUserDTO;
import com.tramo.backend.project.dto.TagCountDTO;
import com.tramo.backend.project.service.ExploreService;
import com.tramo.backend.project.service.FollowService;
import com.tramo.backend.project.service.ProfileFeedService;
import com.tramo.backend.project.service.ProfileService;
import com.tramo.backend.project.service.PublicProjectService;
import com.tramo.backend.project.service.ProjectPublishService;
import com.tramo.backend.user.entity.User;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/public")
public class PublicProjectController {
    private final ProjectPublishService publishService;
    private final ExploreService exploreService;
    private final PublicProjectService publicProjectService;
    private final ProfileService profileService;
    private final ProfileFeedService profileFeedService;
    private final FollowService followService;
    private final ProjectIdCodec projectIdCodec;

    public PublicProjectController(ProjectPublishService publishService, ExploreService exploreService, PublicProjectService publicProjectService, ProfileService profileService, ProfileFeedService profileFeedService, FollowService followService, ProjectIdCodec projectIdCodec) {
        this.publishService = publishService;
        this.exploreService = exploreService;
        this.publicProjectService = publicProjectService;
        this.profileService = profileService;
        this.profileFeedService = profileFeedService;
        this.followService = followService;
        this.projectIdCodec = projectIdCodec;
    }

    @GetMapping("/project/{id}")
    public ResponseEntity<PublicProjectResponseDTO> getPublic(@PathVariable String id,
                                                                @AuthenticationPrincipal User user,
                                                                @RequestHeader(value = "X-Anon-Id", required = false) String anonId) {
        return ResponseEntity.ok(publicProjectService.getPublicProject(projectIdCodec.decode(id), user, anonId));
    }

    @GetMapping("/project/{id}/versions/{snapshotId}")
    public ResponseEntity<ProjectSnapshotDetailDTO> getPublicVersion(@PathVariable String id, @PathVariable Long snapshotId,
                                                                        @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(publishService.getPublicSnapshotDetail(projectIdCodec.decode(id), snapshotId, user));
    }

    @GetMapping("/explore")
    public ResponseEntity<ExploreBundleDTO> getExploreBundle(
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "recent") String sort,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "10") int size,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(exploreService.getExploreBundle(q, sort, page, size, user));
    }

    @GetMapping("/tags")
    public ResponseEntity<List<TagCountDTO>> getHotTopics() {
        return ResponseEntity.ok(exploreService.getHotTopics(5));
    }

    @GetMapping("/sitemap/projects")
    public ResponseEntity<List<SitemapProjectDTO>> getSitemapProjects() {
        return ResponseEntity.ok(publicProjectService.getSitemapProjects());
    }

    @GetMapping("/sitemap/users")
    public ResponseEntity<List<SitemapUserDTO>> getSitemapUsers() {
        return ResponseEntity.ok(publicProjectService.getSitemapUsers());
    }

    @GetMapping("/users/{username}")
    public ResponseEntity<PublicProfileDTO> getPublicProfile(@PathVariable String username,
                                                              @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(profileService.getPublicProfile(username, user));
    }

    @GetMapping("/users/{username}/followers")
    public ResponseEntity<PageResponseDTO<FollowUserDTO>> getFollowers(@PathVariable String username,
                                                                         @RequestParam(defaultValue = "0") int page,
                                                                         @RequestParam(defaultValue = "20") int size,
                                                                         @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(followService.getFollowers(username, user, page, size));
    }

    @GetMapping("/users/{username}/following")
    public ResponseEntity<PageResponseDTO<FollowUserDTO>> getFollowing(@PathVariable String username,
                                                                         @RequestParam(defaultValue = "0") int page,
                                                                         @RequestParam(defaultValue = "20") int size,
                                                                         @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(followService.getFollowing(username, user, page, size));
    }

    @GetMapping("/users/{username}/published")
    public ResponseEntity<PageResponseDTO<ProjectFeedItemDTO>> getPublished(@PathVariable String username,
                                                                              @RequestParam(defaultValue = "0") int page,
                                                                              @RequestParam(defaultValue = "10") int size,
                                                                              @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(profileFeedService.getPublishedPageForUser(username, user, page, size));
    }

    @GetMapping("/users/{username}/upvoted")
    public ResponseEntity<PageResponseDTO<ProjectFeedItemDTO>> getUpvoted(@PathVariable String username,
                                                                            @RequestParam(defaultValue = "0") int page,
                                                                            @RequestParam(defaultValue = "10") int size,
                                                                            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(profileFeedService.getPublicUpvotedPage(username, user, page, size));
    }
}
