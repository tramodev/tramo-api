package com.tramo.backend.exception;

public class BirthDateAlreadySetException extends RuntimeException {
    public BirthDateAlreadySetException(String message) {
        super(message);
    }
}
