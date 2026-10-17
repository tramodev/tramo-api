package com.tramo.backend.project.dto;

import com.tramo.backend.project.entity.ProjectThumbnailType;
import java.util.List;

public record ProjectVersionData(int schemaVersion, String title, String description, String graphColors,
        ProjectThumbnailType thumbnailType, String thumbnailImageUrl, Long thumbnailTrailId,
        List<String> tags, List<ItemData> items, List<TrailData> trails) {
    public record ItemData(Long id, String title, String type, String titleAlign, String content, boolean unfiled) {}
    public record TrailData(Long id, String title, String description, String visibility,
            Long forkedFromId, List<Long> itemIds) {}
}
