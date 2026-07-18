package com.tramo.backend.project.controller;

import com.tramo.backend.common.ProjectIdCodec;
import com.tramo.backend.project.dto.ExploreBundleDTO;
import com.tramo.backend.project.dto.FollowUserDTO;
import com.tramo.backend.project.dto.PageResponseDTO;
import com.tramo.backend.project.dto.ProjectFeedItemDTO;
import com.tramo.backend.project.dto.PublicProfileDTO;
import com.tramo.backend.project.dto.PublicProjectResponseDTO;
import com.tramo.backend.project.dto.TagCountDTO;
import com.tramo.backend.project.service.ProjectService;
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
    private final ProjectService projectService;
    private final ProjectIdCodec projectIdCodec;

    public PublicProjectController(ProjectService projectService, ProjectIdCodec projectIdCodec) {
        this.projectService = projectService;
        this.projectIdCodec = projectIdCodec;
    }

    @GetMapping("/project/{id}")
    public ResponseEntity<PublicProjectResponseDTO> getPublic(@PathVariable String id,
                                                                @AuthenticationPrincipal User user,
                                                                @RequestHeader(value = "X-Anon-Id", required = false) String anonId) {
        return ResponseEntity.ok(projectService.getPublicProject(projectIdCodec.decode(id), user, anonId));
    }

    @GetMapping("/projects")
    public ResponseEntity<List<ProjectFeedItemDTO>> getFeed(
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "recent") String sort,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(projectService.getPublishedFeed(q, sort, user));
    }

    @GetMapping("/explore")
    public ResponseEntity<ExploreBundleDTO> getExploreBundle(
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "recent") String sort,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "10") int size,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(projectService.getExploreBundle(q, sort, page, size, user));
    }

    @GetMapping("/tags")
    public ResponseEntity<List<TagCountDTO>> getHotTopics() {
        return ResponseEntity.ok(projectService.getHotTopics(10));
    }

    @GetMapping("/users/{username}")
    public ResponseEntity<PublicProfileDTO> getPublicProfile(@PathVariable String username,
                                                              @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(projectService.getPublicProfile(username, user));
    }

    @GetMapping("/users/{username}/followers")
    public ResponseEntity<PageResponseDTO<FollowUserDTO>> getFollowers(@PathVariable String username,
                                                                         @RequestParam(defaultValue = "0") int page,
                                                                         @RequestParam(defaultValue = "20") int size,
                                                                         @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(projectService.getFollowers(username, user, page, size));
    }

    @GetMapping("/users/{username}/following")
    public ResponseEntity<PageResponseDTO<FollowUserDTO>> getFollowing(@PathVariable String username,
                                                                         @RequestParam(defaultValue = "0") int page,
                                                                         @RequestParam(defaultValue = "20") int size,
                                                                         @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(projectService.getFollowing(username, user, page, size));
    }

    @GetMapping("/users/{username}/published")
    public ResponseEntity<PageResponseDTO<ProjectFeedItemDTO>> getPublished(@PathVariable String username,
                                                                              @RequestParam(defaultValue = "0") int page,
                                                                              @RequestParam(defaultValue = "10") int size,
                                                                              @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(projectService.getPublishedPageForUser(username, user, page, size));
    }
}
