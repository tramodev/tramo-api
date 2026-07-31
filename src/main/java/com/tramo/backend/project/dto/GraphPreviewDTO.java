package com.tramo.backend.project.dto;

import com.tramo.backend.trail.dto.AssociationDTO;

import java.util.List;

// Lightweight graph shape for a GRAPH-type thumbnail — item titles + associations only,
// no Lexical content, so feed cards never ship item bodies over the wire.
public record GraphPreviewDTO(
        String trailId,
        String trailTitle,
        List<String> itemIds,
        List<GraphItemDTO> items
) {
    public record GraphItemDTO(String id, String title, List<AssociationDTO> associations) {
    }
}
