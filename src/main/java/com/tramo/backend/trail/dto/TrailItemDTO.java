// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.trail.dto;

import java.util.Date;




public record TrailItemDTO(
        Long id,
        String title,
        String type,
        String titleAlign,
        Date createdDate,
        Date modifiedDate,
        String annotation,
        String associationId
) {
}
