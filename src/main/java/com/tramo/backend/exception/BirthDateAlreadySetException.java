// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.exception;

public class BirthDateAlreadySetException extends RuntimeException {
    public BirthDateAlreadySetException(String message) {
        super(message);
    }
}
