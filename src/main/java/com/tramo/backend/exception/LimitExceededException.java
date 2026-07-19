package com.tramo.backend.exception;

/** A free-tier usage limit was hit (storage quota, publish rate). Mapped to HTTP 429. */
public class LimitExceededException extends RuntimeException {
    public LimitExceededException(String message) {
        super(message);
    }
}
