package com.tramo.backend.content.dto;

import java.time.LocalDate;

public record BlogPostSummaryDTO(String slug, String title, String summary, String category,
                                 LocalDate publishedDate) {}
