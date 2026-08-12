package com.tramo.backend.trail.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record TrailOrderRequestDTO(
        @NotEmpty List<Long> itemIds
) {
}
