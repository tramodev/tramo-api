// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.project.entity;

public enum ProjectRole {
    VIEWER, EDITOR, OWNER;

    public boolean atLeast(ProjectRole min) {
        return compareTo(min) >= 0;
    }
}
