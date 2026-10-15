package com.tramo.backend.content.service;

import com.tramo.backend.content.dto.*;
import com.tramo.backend.content.entity.*;
import com.tramo.backend.content.repository.*;
import com.tramo.backend.exception.ContentConflictException;
import com.tramo.backend.exception.ResourceNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
public class ContentService {
    private final BlogPostRepository blog;
    private final ChangelogEntryRepository changelog;
    private final RoadmapItemRepository roadmap;

    public ContentService(BlogPostRepository blog, ChangelogEntryRepository changelog, RoadmapItemRepository roadmap) {
        this.blog = blog;
        this.changelog = changelog;
        this.roadmap = roadmap;
    }

    public List<BlogPostSummaryDTO> listPublishedBlog() {
        return blog.findPublishedSummaries();
    }

    public BlogPostDTO getPublishedPost(String slug) {
        return blogDTO(blog.findBySlugAndPublishedTrue(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Blog post not found")));
    }

    public List<ChangelogEntryDTO> listPublishedChangelog() {
        return changelog.findByPublishedTrueOrderByPublishedDateDescIdDesc().stream().map(this::changelogDTO).toList();
    }

    public List<RoadmapItemDTO> listPublishedRoadmap() {
        return roadmap.findByPublishedTrueOrderByPositionAscIdAsc().stream().map(this::roadmapDTO).toList();
    }

    @PreAuthorize("hasAuthority('ADMIN')")
    @Transactional(readOnly = true)
    public AdminContentDTO listAdminContent() {
        return new AdminContentDTO(
                blog.findAllByOrderByPublishedDateDescIdDesc().stream().map(this::blogDTO).toList(),
                changelog.findAllByOrderByPublishedDateDescIdDesc().stream().map(this::changelogDTO).toList(),
                roadmap.findAllByOrderByPositionAscIdAsc().stream().map(this::roadmapDTO).toList());
    }

    @PreAuthorize("hasAuthority('ADMIN')")
    @Transactional
    public BlogPostDTO saveBlog(Long id, BlogPostRequest request) {
        BlogPost post = id == null ? new BlogPost() : blog.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Blog post not found"));
        post.setSlug(request.slug());
        post.setTitle(request.title().trim());
        post.setSummary(request.summary().trim());
        post.setCategory(request.category().trim());
        post.setContent(request.content());
        post.setPublished(request.published());
        post.setPublishedDate(request.publishedDate());
        try {
            return blogDTO(blog.saveAndFlush(post));
        } catch (DataIntegrityViolationException ex) {
            throw new ContentConflictException();
        }
    }

    @PreAuthorize("hasAuthority('ADMIN')")
    @Transactional
    public ChangelogEntryDTO saveChangelog(Long id, ChangelogEntryRequest request) {
        ChangelogEntry entry = id == null ? new ChangelogEntry() : changelog.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Changelog entry not found"));
        entry.setTitle(request.title().trim());
        entry.setContent(request.content());
        entry.setPublished(request.published());
        entry.setPublishedDate(request.publishedDate());
        return changelogDTO(changelog.save(entry));
    }

    @PreAuthorize("hasAuthority('ADMIN')")
    @Transactional
    public RoadmapItemDTO saveRoadmap(Long id, RoadmapItemRequest request) {
        RoadmapItem item = id == null ? new RoadmapItem() : roadmap.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Roadmap item not found"));
        item.setTitle(request.title().trim());
        item.setDescription(request.description());
        item.setStatus(request.status());
        item.setPosition(request.position());
        item.setPublished(request.published());
        return roadmapDTO(roadmap.save(item));
    }

    @PreAuthorize("hasAuthority('ADMIN')")
    @Transactional
    public void deleteBlog(Long id) {
        blog.delete(blog.findById(id).orElseThrow(() -> new ResourceNotFoundException("Blog post not found")));
    }

    @PreAuthorize("hasAuthority('ADMIN')")
    @Transactional
    public void deleteChangelog(Long id) {
        changelog.delete(changelog.findById(id).orElseThrow(() -> new ResourceNotFoundException("Changelog entry not found")));
    }

    @PreAuthorize("hasAuthority('ADMIN')")
    @Transactional
    public void deleteRoadmap(Long id) {
        roadmap.delete(roadmap.findById(id).orElseThrow(() -> new ResourceNotFoundException("Roadmap item not found")));
    }

    private BlogPostDTO blogDTO(BlogPost post) {
        return new BlogPostDTO(post.getId(), post.getSlug(), post.getTitle(), post.getSummary(), post.getCategory(),
                post.getContent(), post.isPublished(), post.getPublishedDate());
    }

    private ChangelogEntryDTO changelogDTO(ChangelogEntry entry) {
        return new ChangelogEntryDTO(entry.getId(), entry.getTitle(), entry.getContent(), entry.isPublished(), entry.getPublishedDate());
    }

    private RoadmapItemDTO roadmapDTO(RoadmapItem item) {
        return new RoadmapItemDTO(item.getId(), item.getTitle(), item.getDescription(), item.getStatus(), item.getPosition(), item.isPublished());
    }
}
