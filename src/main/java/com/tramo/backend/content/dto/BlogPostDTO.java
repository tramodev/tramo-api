package com.tramo.backend.content.dto;

import java.time.LocalDate;

public record BlogPostDTO(Long id, String slug, String title, String summary, String category,
                          String content, boolean published, LocalDate publishedDate) {}
