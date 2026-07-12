package com.mypath.backend.project.controller;

import com.mypath.backend.project.dto.ProjectFeedItemDTO;
import com.mypath.backend.project.dto.PublicProjectResponseDTO;
import com.mypath.backend.project.dto.TagCountDTO;
import com.mypath.backend.project.service.ProjectService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    public ResponseEntity<PublicProjectResponseDTO> getPublic(@PathVariable Long id) {
        return ResponseEntity.ok(projectService.getPublicProject(id));
    }

    @GetMapping("/projects")
    public ResponseEntity<List<ProjectFeedItemDTO>> getFeed(
            @RequestParam(required = false) String q) {
        return ResponseEntity.ok(projectService.getPublishedFeed(q));
    }

    @GetMapping("/tags")
    public ResponseEntity<List<TagCountDTO>> getHotTopics() {
        return ResponseEntity.ok(projectService.getHotTopics(10));
    }
}
