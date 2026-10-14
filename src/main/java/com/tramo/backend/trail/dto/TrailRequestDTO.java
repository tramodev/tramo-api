// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.trail.dto;

import lombok.Getter;
import lombok.Setter;
import jakarta.validation.constraints.Size;
import com.tramo.backend.common.ContentLimits;

@Getter
@Setter
public class TrailRequestDTO {
    private String title;
    @Size(max = ContentLimits.TRAIL_DESCRIPTION_CHARACTERS, message = "TRAIL_DESCRIPTION_SIZE_INVALID")
    private String description;
    private String visibility;
}
