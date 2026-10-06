// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.trail.dto;

import java.util.List;

public record ProjectTextStatsDTO(long words, long characters, List<ItemTextStatsDTO> items) {}
