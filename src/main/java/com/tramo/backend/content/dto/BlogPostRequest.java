package com.tramo.backend.content.dto;

import jakarta.validation.constraints.*;
import java.time.LocalDate;

public record BlogPostRequest(
        @NotBlank @Size(max = 120) @Pattern(regexp = "[a-z0-9]+(?:-[a-z0-9]+)*") String slug,
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 1000) String summary,
        @NotBlank @Size(max = 80) String category,
        @NotNull @Size(max = 200000) String content,
        @NotNull Boolean published,
        @NotNull LocalDate publishedDate) {
    @AssertTrue
    public boolean isPublishedContentPresent() {
        return !Boolean.TRUE.equals(published) || (content != null && !content.isBlank());
    }
}
