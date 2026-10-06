package com.tramo.backend.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class ErrorResponse {
    private int status;
    private String message;
    private LocalDateTime timestamp;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String code;

    public ErrorResponse(int status, String message, LocalDateTime timestamp) {
        this.status = status;
        this.message = message;
        this.timestamp = timestamp;
    }
}
