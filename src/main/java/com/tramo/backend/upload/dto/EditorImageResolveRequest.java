package com.tramo.backend.upload.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record EditorImageResolveRequest(@NotNull @Size(min = 1, max = 100) List<@NotNull UUID> imageIds,
        @Positive Long snapshotId) {}
