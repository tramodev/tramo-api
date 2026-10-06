// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.exception;

public class CaptchaVerificationException extends RuntimeException {
    public CaptchaVerificationException(String message) {
        super(message);
    }
}
