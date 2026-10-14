package com.tramo.backend.exception;

public class PayloadTooLargeException extends RuntimeException {
    public PayloadTooLargeException() {
        super("Notes must not exceed 1 MiB and requests must not exceed 4 MiB.");
    }
}
