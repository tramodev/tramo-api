// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.project.dto;

import java.util.Date;

public record ShareLinkDTO(String role, Date createdAt, Date lastUsedAt, int useCount, String token) {}
