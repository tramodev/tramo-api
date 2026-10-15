package com.tramo.backend.exception;

public class ContentConflictException extends RuntimeException {
    public ContentConflictException() {
        super("A blog post already uses this slug");
    }
}
