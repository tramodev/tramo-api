package com.tramo.backend.common;

import org.slf4j.Logger;
import org.springframework.web.client.RestClientResponseException;
import software.amazon.awssdk.awscore.exception.AwsServiceException;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.UUID;

public final class SafeLog {
    private SafeLog() {
    }

    public static String failure(Logger logger, String event, String code, Throwable failure) {
        String trackingId = UUID.randomUUID().toString();
        String diagnostic = diagnostic(failure);
        if ("INTERNAL_ERROR".equals(code) || "AUTH_UNAVAILABLE".equals(code)) {
            logger.error("event={} code={} trackingId={} {}", event, code, trackingId, diagnostic);
        } else {
            logger.warn("event={} code={} trackingId={} {}", event, code, trackingId, diagnostic);
        }
        return trackingId;
    }

    private static String diagnostic(Throwable failure) {
        StringBuilder result = new StringBuilder(failure == null ? "exceptionType=none" : "");
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int depth = 0; failure != null && depth < 8 && seen.add(failure); depth++, failure = failure.getCause()) {
            result.append(" exceptionType=").append(failure.getClass().getName());
            if (failure instanceof RestClientResponseException response) {
                result.append(" upstreamStatus=").append(response.getStatusCode().value());
            } else if (failure instanceof AwsServiceException response) {
                result.append(" upstreamStatus=").append(response.statusCode());
            }
            int frames = 0;
            for (StackTraceElement frame : failure.getStackTrace()) {
                if (frame.getClassName().startsWith("com.tramo.backend.") && frames++ < 5) {
                    result.append(" at=").append(frame.getClassName()).append('.').append(frame.getMethodName())
                            .append(':').append(frame.getLineNumber());
                }
            }
        }
        return result.toString();
    }
}
