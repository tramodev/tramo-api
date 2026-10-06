// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.upload.dto;

import java.util.UUID;

public record EditorImagePresignResponse(UUID imageId, String uploadUrl) {}
