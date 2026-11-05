package com.tramo.backend.exception;

public class CollabActiveException extends RuntimeException {
    public CollabActiveException() {
        super("This note is open for live editing. Reload to keep editing it there.");
    }
}
