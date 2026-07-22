package com.tramo.backend.trail.dto;

// "blaze": set the annotation and the association used to reach a step.
// associationId may be null (a deliberate jump with no graph association).
public record StepUpdateRequestDTO(
        String annotation,
        Long associationId
) {
}
