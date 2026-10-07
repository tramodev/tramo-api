package com.tramo.backend.project.dto;

import java.util.List;
import java.util.Map;

public record ProjectExportDTO(int formatVersion, String exportedAt, ProjectData project,
        List<ItemData> items, List<TrailData> trails, List<Long> looseItemIds,
        List<AssociationData> associations, Map<String, String> assets, List<String> warnings) {
    public record ProjectData(String id, String title, String description, String visibility, List<String> tags,
            String thumbnailType, String thumbnailImageUrl, Long thumbnailTrailId, String createdAt, String modifiedAt) {}
    public record ItemData(Long id, String title, String type, String titleAlign, String content,
            boolean unfiled, String createdAt, String modifiedAt) {}
    public record TrailData(Long id, String title, String description, String visibility, int version,
            Long forkedFromId, List<StepData> steps) {}
    public record StepData(Long id, Long itemId, int orderIndex, String annotation, Long associationId) {}
    public record AssociationData(Long id, Long sourceItemId, String type, String targetType, Long targetId) {}
}
