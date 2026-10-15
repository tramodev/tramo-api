package com.tramo.backend.content.controller;

import com.tramo.backend.content.dto.*;
import com.tramo.backend.content.service.ContentService;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/public/content")
public class PublicContentController {
    private final ContentService content;

    public PublicContentController(ContentService content) {
        this.content = content;
    }

    @GetMapping("/blog")
    public List<BlogPostSummaryDTO> blog() {
        return content.listPublishedBlog();
    }

    @GetMapping("/blog/{slug}")
    public BlogPostDTO post(@PathVariable String slug) {
        return content.getPublishedPost(slug);
    }

    @GetMapping("/changelog")
    public List<ChangelogEntryDTO> changelog() {
        return content.listPublishedChangelog();
    }

    @GetMapping("/roadmap")
    public List<RoadmapItemDTO> roadmap() {
        return content.listPublishedRoadmap();
    }
}
