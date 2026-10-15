package com.tramo.backend.content;

import com.tramo.backend.content.dto.*;
import com.tramo.backend.content.entity.*;
import com.tramo.backend.content.repository.*;
import com.tramo.backend.content.service.ContentService;
import com.tramo.backend.exception.ContentConflictException;
import com.tramo.backend.exception.ResourceNotFoundException;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import java.time.LocalDate;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContentServiceTest {
    private final BlogPostRepository blog = mock(BlogPostRepository.class);
    private final ChangelogEntryRepository changelog = mock(ChangelogEntryRepository.class);
    private final RoadmapItemRepository roadmap = mock(RoadmapItemRepository.class);
    private ContentService content;

    @BeforeEach
    void setUp() {
        content = new ContentService(blog, changelog, roadmap);
    }

    @Test
    void savesBlogAndReportsDuplicateSlugs() {
        when(blog.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        var request = new BlogPostRequest("first-post", " Title ", " Summary ", " Ideas ", "Body", true, LocalDate.now());
        var saved = content.saveBlog(null, request);
        assertThat(saved.title()).isEqualTo("Title");
        assertThat(saved.content()).isEqualTo("Body");
        assertThat(saved.published()).isTrue();
        when(blog.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate"));
        assertThatThrownBy(() -> content.saveBlog(null, request)).isInstanceOf(ContentConflictException.class);
    }

    @Test
    void publicDetailOnlyLooksUpPublishedPosts() {
        when(blog.findBySlugAndPublishedTrue("draft")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> content.getPublishedPost("draft")).isInstanceOf(ResourceNotFoundException.class);
        verify(blog).findBySlugAndPublishedTrue("draft");
        verifyNoMoreInteractions(blog);
    }

    @Test
    void editsRoadmapWithoutCreatingANewItem() {
        RoadmapItem item = new RoadmapItem();
        item.setId(7L);
        when(roadmap.findById(7L)).thenReturn(Optional.of(item));
        when(roadmap.save(any())).thenAnswer(call -> call.getArgument(0));
        var saved = content.saveRoadmap(7L, new RoadmapItemRequest("AI", "Connections", "IN_PROGRESS", 2, true));
        assertThat(saved.id()).isEqualTo(7L);
        assertThat(saved.status()).isEqualTo("IN_PROGRESS");
        assertThat(saved.position()).isEqualTo(2);
    }

    @Test
    void validatesSlugsPublishedBodiesAndRoadmapStatus() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var draft = new BlogPostRequest("first-post", "Title", "Summary", "Ideas", "", false, LocalDate.now());
            assertThat(validator.validate(draft)).isEmpty();
            assertThat(validator.validate(new BlogPostRequest("Bad/slug", "Title", "Summary", "Ideas", "Body", false, LocalDate.now()))).isNotEmpty();
            assertThat(validator.validate(new BlogPostRequest("first-post", "Title", "Summary", "Ideas", " ", true, LocalDate.now()))).isNotEmpty();
            assertThat(validator.validate(new RoadmapItemRequest("AI", "Connections", "UNKNOWN", -1, true))).hasSize(2);
        }
    }
}
