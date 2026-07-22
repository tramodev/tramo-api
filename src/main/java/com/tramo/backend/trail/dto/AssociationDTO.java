package com.tramo.backend.trail.dto;

public record AssociationDTO(
        String id,
        String type,
        String targetType,
        String targetId,
        String targetTitle
) {
}
