// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.exception;


import com.tramo.backend.common.SafeLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final Set<String> PUBLIC_MESSAGES = Set.of(
            "Invalid refresh token", "Refresh token expired", "Invalid or expired reset link",
            "Invalid or expired verification link", "Invalid Google token", "Google account email is not verified",
            "Animated GIF avatars are a supporter perk. Upgrade to use one.",
            "Profile banners are a supporter perk. Upgrade to use one.",
            "Upload throughput limit reached. Try again later.", "Too many attempts. Try again later.",
            "You're creating tags too fast. Try again later."
    );

    private static String publicMessage(Exception ex, String fallback) {
        String message = ex.getMessage();
        return message != null && PUBLIC_MESSAGES.contains(message) ? message : fallback;
    }

    private <T extends ErrorResponse> ResponseEntity<T> handled(Exception ex, T error, HttpStatus status) {
        if (error.getCode() == null) error.setCode(status.name());
        String trackingId = SafeLog.failure(log, "request_rejected", error.getCode(), ex);
        return ResponseEntity.status(status).header("X-Tracking-Id", trackingId).body(error);
    }

    @ExceptionHandler(UserAlreadyExistsException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ResponseEntity<ValidationErrorResponse> handleUserAlreadyExists(UserAlreadyExistsException ex) {
        ValidationErrorResponse error = new ValidationErrorResponse(
                HttpStatus.CONFLICT.value(),
                "Account already exists",
                LocalDateTime.now(),
                Map.of("email".equals(ex.getField()) ? "email" : "username", "Account already exists")
        );
        return handled(ex, error, HttpStatus.CONFLICT);
    }

    @ExceptionHandler(UsernameNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ResponseEntity<ErrorResponse> handleUsernameNotFound(UsernameNotFoundException ex) {
        ErrorResponse error = new ErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                "User not found",
                LocalDateTime.now()
        );
        return handled(ex, error, HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(BadCredentialsException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ResponseEntity<ErrorResponse> handleBadCredentials(BadCredentialsException ex) {
        ErrorResponse error = new ErrorResponse(
                HttpStatus.UNAUTHORIZED.value(),
                "Invalid username or password",
                LocalDateTime.now()
        );
        return handled(ex, error, HttpStatus.UNAUTHORIZED);
    }

    @ExceptionHandler(DisabledException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ResponseEntity<ErrorResponse> handleDisabled(DisabledException ex) {
        ErrorResponse error = new ErrorResponse(
                HttpStatus.FORBIDDEN.value(),
                "Please verify your email before logging in.",
                LocalDateTime.now()
        );
        return handled(ex, error, HttpStatus.FORBIDDEN);
    }

    @ExceptionHandler(LockedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ResponseEntity<ErrorResponse> handleLocked(LockedException ex) {
        ErrorResponse error = new ErrorResponse(
                HttpStatus.FORBIDDEN.value(),
                "This account has been banned.",
                LocalDateTime.now()
        );
        return handled(ex, error, HttpStatus.FORBIDDEN);
    }

    @ExceptionHandler(InvalidTokenException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ResponseEntity<ErrorResponse> handleInvalidToken(InvalidTokenException ex) {
        ErrorResponse error = new ErrorResponse(
                HttpStatus.UNAUTHORIZED.value(),
                publicMessage(ex, "Invalid or expired token"),
                LocalDateTime.now()
        );
        return handled(ex, error, HttpStatus.UNAUTHORIZED);
    }

    @ExceptionHandler(CaptchaVerificationException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ResponseEntity<ErrorResponse> handleCaptchaVerification(CaptchaVerificationException ex) {
        ErrorResponse error = new ErrorResponse(
                HttpStatus.FORBIDDEN.value(),
                "Captcha verification failed",
                LocalDateTime.now()
        );
        return handled(ex, error, HttpStatus.FORBIDDEN);
    }

    @ExceptionHandler(UnderageRegistrationException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ResponseEntity<ErrorResponse> handleUnderageRegistration(UnderageRegistrationException ex) {
        ErrorResponse error = new ErrorResponse(
                HttpStatus.FORBIDDEN.value(),
                "Minimum age requirement not met",
                LocalDateTime.now()
        );
        return handled(ex, error, HttpStatus.FORBIDDEN);
    }

    @ExceptionHandler(BirthDateAlreadySetException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ResponseEntity<ErrorResponse> handleBirthDateAlreadySet(BirthDateAlreadySetException ex) {
        ErrorResponse error = new ErrorResponse(
                HttpStatus.CONFLICT.value(),
                "Birth date already set",
                LocalDateTime.now()
        );
        return handled(ex, error, HttpStatus.CONFLICT);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ResponseEntity<ErrorResponse> handleResourceNotFound(ResourceNotFoundException ex) {
        ErrorResponse error = new ErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                "Resource not found",
                LocalDateTime.now()
        );
        return handled(ex, error, HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        ErrorResponse error = new ErrorResponse(
                HttpStatus.FORBIDDEN.value(),
                "Access denied",
                LocalDateTime.now()
        );
        return handled(ex, error, HttpStatus.FORBIDDEN);
    }

    @ExceptionHandler(LimitExceededException.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public ResponseEntity<ErrorResponse> handleLimitExceeded(LimitExceededException ex) {
        ErrorResponse error = new ErrorResponse(
                HttpStatus.TOO_MANY_REQUESTS.value(),
                publicMessage(ex, "Limit exceeded"),
                LocalDateTime.now()
        );
        return handled(ex, error, HttpStatus.TOO_MANY_REQUESTS);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        RequestErrorCode code = ex instanceof RequestValidationException validation
                ? validation.getCode() : RequestErrorCode.INVALID_REQUEST;
        ErrorResponse error = new ErrorResponse(HttpStatus.BAD_REQUEST.value(), code.getMessage(), LocalDateTime.now());
        error.setCode(code.name());
        return handled(ex, error, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ResponseEntity<ValidationErrorResponse> handleValidationExceptions(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        Map<String, String> errorCodes = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach((error) -> {
            String fieldName = error.getField();
            RequestErrorCode code = RequestErrorCode.validation(error.getDefaultMessage(), error.getCode());
            errors.put(fieldName, code.getMessage());
            errorCodes.put(fieldName, code.name());
        });

        ValidationErrorResponse errorResponse = new ValidationErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "Validation failed",
                LocalDateTime.now(),
                errors
        );
        errorResponse.setCode("VALIDATION_FAILED");
        errorResponse.setErrorCodes(errorCodes);
        return handled(ex, errorResponse, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleInvalidParameter(MethodArgumentTypeMismatchException ex) {
        return handled(ex, new ErrorResponse(400, "Invalid request parameter", LocalDateTime.now()), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ResponseEntity<ErrorResponse> handleMalformedRequestBody(HttpMessageNotReadableException ex) {
        ErrorResponse error = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "Malformed request body",
                LocalDateTime.now()
        );
        return handled(ex, error, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler({AsyncRequestTimeoutException.class, AsyncRequestNotUsableException.class})
    public void handleDeadAsyncRequest() {
    }

    private static boolean clientIsGone(Throwable ex) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = ex; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof AsyncRequestNotUsableException || cause instanceof AsyncRequestTimeoutException) {
                return true;
            }
        }
        return false;
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ResponseEntity<ErrorResponse> handleGlobalException(Exception ex) {
        if (clientIsGone(ex)) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        String trackingId = SafeLog.failure(log, "request_failed", "INTERNAL_ERROR", ex);
        ErrorResponse error = new ErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "An unexpected error occurred",
                LocalDateTime.now()
        );
        error.setCode("INTERNAL_ERROR");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).header("X-Tracking-Id", trackingId).body(error);
    }

}
