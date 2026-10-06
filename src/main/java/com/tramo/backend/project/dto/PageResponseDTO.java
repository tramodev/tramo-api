// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.project.dto;

import java.util.List;

public record PageResponseDTO<T>(List<T> content, boolean hasMore) {
}
