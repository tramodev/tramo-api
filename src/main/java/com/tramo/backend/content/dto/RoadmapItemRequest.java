package com.tramo.backend.content.dto;

import jakarta.validation.constraints.*;

public record RoadmapItemRequest(@NotBlank @Size(max = 200) String title,
                                 @NotBlank @Size(max = 20000) String description,
                                 @NotNull @Pattern(regexp = "PLANNED|IN_PROGRESS|COMPLETED") String status,
                                 @NotNull @Min(0) Integer position,
                                 @NotNull Boolean published) {}
