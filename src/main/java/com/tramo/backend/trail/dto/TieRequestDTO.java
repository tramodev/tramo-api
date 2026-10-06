// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.trail.dto;

import com.tramo.backend.trail.entity.AssociationTargetType;
import com.tramo.backend.trail.entity.AssociationType;
import jakarta.validation.constraints.NotNull;

public record TieRequestDTO(
        @NotNull AssociationType type,
        @NotNull AssociationTargetType targetType,
        @NotNull Long targetId
) {
}
