// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.project.snapshot;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
    public static final int CURRENT_SCHEMA_VERSION = 5;

    public List<ItemData> looseItems() {
        return looseItems == null ? List.of() : looseItems;
    }

    public record TrailData(Long id, String title, String description, String visibility, int version,
                             Long forkedFromId, List<ItemData> items) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ItemData(Long id, String title, String type, String titleAlign, String content) {
    }
}
