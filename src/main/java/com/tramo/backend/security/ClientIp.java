// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

@Component
public class ClientIp {
    private final byte[] signingKey;

    public ClientIp(@Value("${app.security.bff-signing-key:}") String signingKey) {
        this.signingKey = signingKey.isBlank() ? new byte[0] : Base64.getDecoder().decode(signingKey);
        if (this.signingKey.length > 0 && this.signingKey.length < 32) {
            throw new IllegalArgumentException("BFF signing key must contain at least 32 bytes");
        }
    }

    public boolean requiresSignature(HttpServletRequest request) {
        if (signingKey.length == 0) return false;
        String path = request.getRequestURI();
        return !("GET".equals(request.getMethod()) && ("/actuator/health".equals(path)
                || "/api/auth/patreon/callback".equals(path)))
                && !("POST".equals(request.getMethod()) && "/api/webhooks/patreon".equals(path));
    }

    public boolean isTrusted(HttpServletRequest request) {
        if (signingKey.length == 0) return false;
        String ip = request.getHeader("X-Tramo-Client-IP");
        String signature = request.getHeader("X-Tramo-Signature");
        if (ip == null || ip.length() > 45 || !ip.matches("[0-9a-fA-F:.]+")
                || signature == null || signature.length() != 64) return false;
        try {
            String target = request.getRequestURI()
                    + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
            String payload = ip + "\n" + request.getMethod() + "\n" + target;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return MessageDigest.isEqual(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)),
                    HexFormat.of().parseHex(signature));
        } catch (IllegalArgumentException ex) {
            return false;
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("BFF signature verification unavailable", ex);
        }
    }

    public String from(HttpServletRequest request) {
        return isTrusted(request) ? request.getHeader("X-Tramo-Client-IP") : request.getRemoteAddr();
    }
}
