// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.project.dto;

import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SetThumbnailRequestDTO {
    @Pattern(regexp = "NONE|GRAPH|PROJECT_IMAGE|DEDICATED", message = "THUMBNAIL_TYPE_INVALID")
    private String type;

    private String imageUrl;
}
