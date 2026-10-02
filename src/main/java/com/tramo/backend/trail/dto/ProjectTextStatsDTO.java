package com.tramo.backend.trail.dto;

import java.util.List;

public record ProjectTextStatsDTO(long words, long characters, List<ItemTextStatsDTO> items) {}
