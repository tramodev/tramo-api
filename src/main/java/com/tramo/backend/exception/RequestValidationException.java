package com.tramo.backend.exception;

public final class RequestValidationException extends IllegalArgumentException {
    private final RequestErrorCode code;

    public RequestValidationException(RequestErrorCode code) {
        super(code.getMessage());
        this.code = code;
    }

    public RequestValidationException(RequestErrorCode code, Throwable cause) {
        super(code.getMessage(), cause);
        this.code = code;
    }

    public RequestErrorCode getCode() {
        return code;
    }
}
