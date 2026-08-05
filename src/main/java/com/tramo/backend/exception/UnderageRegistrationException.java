package com.tramo.backend.exception;

public class UnderageRegistrationException extends RuntimeException {
    public UnderageRegistrationException(String message) {
        super(message);
    }
}
