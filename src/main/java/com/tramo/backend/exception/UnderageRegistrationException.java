// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.exception;

public class UnderageRegistrationException extends RuntimeException {
    public UnderageRegistrationException(String message) {
        super(message);
    }
}
