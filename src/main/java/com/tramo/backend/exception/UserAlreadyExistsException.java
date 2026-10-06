// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.exception;


public class UserAlreadyExistsException extends RuntimeException {
    private final String field;

    public UserAlreadyExistsException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}

