package com.tramo.backend.exception;

public class PreconditionRequiredException extends RuntimeException {
    public PreconditionRequiredException(String message) { super(message); }
}
