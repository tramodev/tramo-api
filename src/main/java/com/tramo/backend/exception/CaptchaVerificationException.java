package com.tramo.backend.exception;

public class CaptchaVerificationException extends RuntimeException {
    public CaptchaVerificationException(String message) {
        super(message);
    }
}
