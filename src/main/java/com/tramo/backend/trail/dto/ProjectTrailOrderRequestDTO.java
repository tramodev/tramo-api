package com.tramo.backend.trail.dto;

import jakarta.validation.constraints.*;
import java.util.List;

public record ProjectTrailOrderRequestDTO(@NotNull @Size(max=5000) List<@NotNull @Positive Long> trailIds) {}
