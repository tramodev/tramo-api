package com.tramo.backend.content.dto;

import java.time.LocalDate;

public record ChangelogEntryDTO(Long id, String title, String content, boolean published, LocalDate publishedDate) {}
