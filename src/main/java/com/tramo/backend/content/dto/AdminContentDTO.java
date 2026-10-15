package com.tramo.backend.content.dto;

import java.util.List;

public record AdminContentDTO(List<BlogPostDTO> blog, List<ChangelogEntryDTO> changelog, List<RoadmapItemDTO> roadmap) {}
