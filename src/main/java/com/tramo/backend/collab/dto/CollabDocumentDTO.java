// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.collab.dto;

public record CollabDocumentDTO(byte[] state, String content, int extractionEpoch) {
}
