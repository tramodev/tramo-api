package com.tramo.backend.content.controller;

import com.tramo.backend.content.dto.*;
import com.tramo.backend.content.service.ContentService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/content")
@PreAuthorize("hasAuthority('ADMIN')")
public class AdminContentController {
    private final ContentService content;

    public AdminContentController(ContentService content) {
        this.content = content;
    }

    @GetMapping
    public AdminContentDTO list() {
        return content.listAdminContent();
    }

    @PostMapping("/blog")
    public BlogPostDTO createBlog(@Valid @RequestBody BlogPostRequest request) {
        return content.saveBlog(null, request);
    }

    @PutMapping("/blog/{id}")
    public BlogPostDTO updateBlog(@PathVariable Long id, @Valid @RequestBody BlogPostRequest request) {
        return content.saveBlog(id, request);
    }

    @DeleteMapping("/blog/{id}")
    public void deleteBlog(@PathVariable Long id) {
        content.deleteBlog(id);
    }

    @PostMapping("/changelog")
    public ChangelogEntryDTO createChangelog(@Valid @RequestBody ChangelogEntryRequest request) {
        return content.saveChangelog(null, request);
    }

    @PutMapping("/changelog/{id}")
    public ChangelogEntryDTO updateChangelog(@PathVariable Long id, @Valid @RequestBody ChangelogEntryRequest request) {
        return content.saveChangelog(id, request);
    }

    @DeleteMapping("/changelog/{id}")
    public void deleteChangelog(@PathVariable Long id) {
        content.deleteChangelog(id);
    }

    @PostMapping("/roadmap")
    public RoadmapItemDTO createRoadmap(@Valid @RequestBody RoadmapItemRequest request) {
        return content.saveRoadmap(null, request);
    }

    @PutMapping("/roadmap/{id}")
    public RoadmapItemDTO updateRoadmap(@PathVariable Long id, @Valid @RequestBody RoadmapItemRequest request) {
        return content.saveRoadmap(id, request);
    }

    @DeleteMapping("/roadmap/{id}")
    public void deleteRoadmap(@PathVariable Long id) {
        content.deleteRoadmap(id);
    }
}
