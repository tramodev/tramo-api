package com.tramo.backend.trail.dto;

import java.util.List;

public record ItemCopyResponseDTO(ItemResponseDTO item, String content,
                                  List<AssociationDTO> associations, List<TrailItemDTO> steps) {}
