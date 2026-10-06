// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.trail.dto;

public record AssociationDTO(
        String id,
        String type,
        String targetType,
        String targetId,
        String targetTitle
) {
}
