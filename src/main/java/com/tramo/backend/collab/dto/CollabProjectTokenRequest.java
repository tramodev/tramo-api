// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.collab.dto;

import jakarta.validation.constraints.NotBlank;

public record CollabProjectTokenRequest(@NotBlank String projectId) {
}
