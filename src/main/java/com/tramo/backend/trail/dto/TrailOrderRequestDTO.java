// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.trail.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record TrailOrderRequestDTO(
        @NotEmpty List<Long> itemIds
) {
}
