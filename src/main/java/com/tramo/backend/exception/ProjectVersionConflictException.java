package com.tramo.backend.exception;

public class ProjectVersionConflictException extends RuntimeException {
    public ProjectVersionConflictException(String message) { super(message); }
}
