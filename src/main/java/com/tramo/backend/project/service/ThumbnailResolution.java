// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.project.service;

import com.tramo.backend.project.dto.GraphPreviewDTO;

record ThumbnailResolution(String imageUrl, GraphPreviewDTO graph) {
    static final ThumbnailResolution EMPTY = new ThumbnailResolution(null, null);
}
