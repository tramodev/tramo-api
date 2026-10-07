package com.tramo.backend.trail.dto;

import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;

public record ExtractSelectionRequest(@NotNull UUID operationId, @NotBlank @Size(max=300) String title,
        @NotNull @Size(max=2097152) String expectedContent, @Min(0) int extractionEpoch,
        @NotBlank @Size(max=2097152) String sourceContent, @NotBlank @Size(max=2097152) String extractedContent,
        Long trailId, @Size(max=5000) List<Long> expectedOrder) {}
