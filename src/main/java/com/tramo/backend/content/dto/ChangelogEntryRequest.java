package com.tramo.backend.content.dto;

import jakarta.validation.constraints.*;
import java.time.LocalDate;

public record ChangelogEntryRequest(@NotBlank @Size(max = 200) String title,
                                     @NotBlank @Size(max = 20000) String content,
                                     @NotNull Boolean published,
                                     @NotNull LocalDate publishedDate) {}
