package com.tramo.backend.upload.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public record EditorImagePresignRequest(@NotBlank String projectId,
        @NotNull @Pattern(regexp = "image/jpeg|image/png|image/webp|image/gif") String contentType,
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String contentHash,
        @NotNull @Positive Long contentBytes) {}
