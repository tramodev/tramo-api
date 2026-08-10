package com.tramo.backend.project.dto;

import com.tramo.backend.trail.dto.AssociationDTO;

import java.util.List;

public record GraphPreviewDTO(
        String trailId,
        String trailTitle,
        List<String> itemIds,
        List<GraphItemDTO> items
) {
    public record GraphItemDTO(String id, String title, List<AssociationDTO> associations) {
    }
}
