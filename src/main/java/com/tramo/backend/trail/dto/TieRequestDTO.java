package com.tramo.backend.trail.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record TieRequestDTO(@NotNull Long targetId, @Size(max = 4002) String text) {}
