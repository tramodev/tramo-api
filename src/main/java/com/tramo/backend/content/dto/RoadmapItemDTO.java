package com.tramo.backend.content.dto;

public record RoadmapItemDTO(Long id, String title, String description, String status, int position, boolean published) {}
