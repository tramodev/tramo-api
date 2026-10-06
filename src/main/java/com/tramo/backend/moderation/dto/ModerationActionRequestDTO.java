// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.moderation.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ModerationActionRequestDTO {
    private String reason;
}
