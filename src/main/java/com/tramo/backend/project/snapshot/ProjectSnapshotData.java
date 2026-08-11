package com.tramo.backend.project.snapshot;

import java.util.List;


public record ProjectSnapshotData(
        Integer schemaVersion,
        Long projectId,
        String title,
        String description,
        String visibility,
        String thumbnail,
        String tags,
        List<TrailData> trails,
        List<ItemData> looseItems
) {
    public static final int CURRENT_SCHEMA_VERSION = 2;

    public List<ItemData> looseItems() {
        return looseItems == null ? List.of() : looseItems;
    }

    public record TrailData(Long id, String title, String description, String visibility, int version,
                             Long forkedFromId, List<ItemData> items) {
    }

    public record ItemData(Long id, String title, String type, String titleAlign, String content,
                            String annotation, Long associationId, List<AssociationData> associations) {
    }

    
    public record AssociationData(Long id, String type, String targetType, Long targetId, String targetTitle) {
    }
}
