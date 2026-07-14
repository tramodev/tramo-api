package com.mypath.backend.project.controller;

import com.mypath.backend.project.dto.ExploreBundleDTO;
import com.mypath.backend.project.dto.ProjectFeedItemDTO;
import com.mypath.backend.project.dto.PublicProfileDTO;
import com.mypath.backend.project.dto.PublicProjectResponseDTO;
import com.mypath.backend.project.dto.TagCountDTO;
import com.mypath.backend.project.service.ProjectService;
import com.mypath.backend.user.entity.User;
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

    public PublicProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @GetMapping("/project/{id}")
    public ResponseEntity<PublicProjectResponseDTO> getPublic(@PathVariable Long id,
                                                                @AuthenticationPrincipal User user,
                                                                @RequestHeader(value = "X-Anon-Id", required = false) String anonId) {
        return ResponseEntity.ok(projectService.getPublicProject(id, user, anonId));
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
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(projectService.getExploreBundle(q, sort, user));
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
}
