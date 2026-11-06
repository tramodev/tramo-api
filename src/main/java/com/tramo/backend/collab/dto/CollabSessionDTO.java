// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.collab.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

public record CollabSessionDTO(@NotNull Long userId, Long itemId, Long projectId, String sv, @NotNull String perm) {
    @AssertTrue
    public boolean isSingleTarget() {
        return (itemId == null) != (projectId == null);
    }
}
