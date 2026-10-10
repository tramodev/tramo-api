package com.tramo.backend.project.dto;

import com.tramo.backend.project.entity.ProjectVisibility;
import com.tramo.backend.trail.dto.TrailItemContentDTO;
import java.util.List;

public record EditorBootstrapDTO(String id, String title, String description, String graphColors,
        ProjectVisibility visibility, List<String> tags, List<EditorTrailDTO> trails, List<EditorItemDTO> items,
        Long selectedItemId, Long selectedTrailId, List<TrailItemContentDTO> contents,
        String username, String imageUrl) {
    public record EditorTrailDTO(Long id, String title, String description, int version,
            Long forkedFromId, List<Long> itemIds) {}

    public record EditorItemDTO(Long id, String title, String titleAlign, boolean unfiled,
            long words, long characters) {}
}
