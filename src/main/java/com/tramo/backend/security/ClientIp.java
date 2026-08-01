package com.tramo.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ClientIp {
    private final String trustedHeader;

    public ClientIp(@Value("${app.security.client-ip-header:}") String trustedHeader) {
        this.trustedHeader = trustedHeader;
    }

    public String from(HttpServletRequest request) {
        if (!trustedHeader.isBlank()) {
            String forwarded = request.getHeader(trustedHeader);
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }
}
