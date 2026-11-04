// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.project.dto;

public record ProjectMemberDTO(String username, String imageUrl, String role, boolean viaLink) {}
