package com.tramo.backend.trail.dto;

import java.util.List;

public record ExtractSelectionResponse(ItemResponseDTO item, String content, String sourceContent,
        int extractionEpoch, Long trailId, List<TrailItemDTO> steps) {}
