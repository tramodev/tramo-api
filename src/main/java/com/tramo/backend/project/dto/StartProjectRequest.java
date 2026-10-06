// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.project.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record StartProjectRequest(@NotNull UUID requestId) {}
