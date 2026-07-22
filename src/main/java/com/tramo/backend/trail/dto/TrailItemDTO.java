package com.tramo.backend.trail.dto;

import java.util.Date;

// A step of a trail: the item plus the per-step metadata (annotation + which
// association was used to jump here). The same item can appear in several trails
// with different step metadata, so this is distinct from ItemResponseDTO.
public record TrailItemDTO(
        Long id,
        String title,
        String type,
        String titleAlign,
        Date createdDate,
        Date modifiedDate,
        String annotation,
        String associationId
) {
}
