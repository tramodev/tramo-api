// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.project.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record ProjectImportRequest(@NotNull UUID clientRequestId,
        @NotNull @Size(max = 255) String title,
        @NotNull @Size(max = 50) List<@NotNull @Valid ImportTrail> trails,
        @NotNull @Size(min = 1, max = 200) List<@NotNull @Valid ImportItem> items) {
    public record ImportTrail(@NotNull @Size(max = 255) String title,
            @NotNull @Size(max = 200) List<@NotNull @Pattern(regexp = "\\d{1,19}") String> itemIds) {}
    public record ImportItem(@NotNull @Pattern(regexp = "\\d{1,19}") String id,
            @NotNull @Size(max = 255) String title,
            @NotNull @Pattern(regexp = "left|center|right") String titleAlign,
            @NotNull String content) {}
}
