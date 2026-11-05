// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.collab.dto;

import jakarta.validation.constraints.NotNull;

public record CollabStoreRequest(@NotNull byte[] state, @NotNull String content, @NotNull Integer extractionEpoch,
                                 @NotNull Long authorId, String bootstrapHash) {
}
