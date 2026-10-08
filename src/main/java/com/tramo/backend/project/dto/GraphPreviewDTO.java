// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.project.dto;

import com.tramo.backend.trail.dto.AssociationDTO;

import java.util.List;

public record GraphPreviewDTO(
        List<GraphTrailDTO> trails,
        List<GraphItemDTO> items
) {
    public record GraphTrailDTO(String id, String title, List<String> itemIds) {
    }

    public record GraphItemDTO(String id, String title, List<AssociationDTO> associations) {
    }
}
