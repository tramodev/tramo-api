// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.auth.service;

import com.tramo.backend.exception.LimitExceededException;
import com.tramo.backend.exception.CaptchaVerificationException;
import com.tramo.backend.security.ratelimit.RateLimiterService;
import io.github.bucket4j.Bucket;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Locale;

@Service
public class IdentityRateLimiter {
    private final RateLimiterService rateLimiterService;

    public IdentityRateLimiter(RateLimiterService rateLimiterService) {
        this.rateLimiterService = rateLimiterService;
    }

    public void check(String scope, String identifier, int capacity, int refillTokens) {
        Bucket bucket = rateLimiterService.resolveBucket(
                scope + ":" + identifier.trim().toLowerCase(Locale.ROOT), capacity, refillTokens, Duration.ofMinutes(1));
        if (!bucket.tryConsume(1)) {
            throw new LimitExceededException("Too many attempts. Try again later.");
        }
    }

    public void checkLogin(String username, String ip, String captchaToken, CaptchaVerifier captchaVerifier) {
        if (loginBucket(username, ip).getAvailableTokens() == 0) {
            throw new LimitExceededException("Too many attempts. Try again later.");
        }
        if (loginBucket(username, null).getAvailableTokens() == 0) {
            if (captchaToken == null || captchaToken.isBlank()) {
                throw new CaptchaVerificationException("Captcha verification failed");
            }
            captchaVerifier.verify(captchaToken, "login");
        }
    }

    public void recordLoginFailure(String username, String ip) {
        loginBucket(username, ip).tryConsume(1);
        loginBucket(username, null).tryConsume(1);
    }

    private Bucket loginBucket(String username, String ip) {
        String normalized = username.trim().toLowerCase(Locale.ROOT);
        String key = ip == null ? "login:captcha:" + normalized : "login:failure:" + ip + ":" + normalized;
        int capacity = ip == null ? 5 : 10;
        return rateLimiterService.resolveBucket(key, capacity, capacity, Duration.ofMinutes(1));
    }
}
