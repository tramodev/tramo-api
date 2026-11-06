package com.tramo.backend.trail.dto;

import java.util.List;

public record ExtractSelectionResponse(ItemResponseDTO item, String content, Long trailId, List<TrailItemDTO> steps) {}
