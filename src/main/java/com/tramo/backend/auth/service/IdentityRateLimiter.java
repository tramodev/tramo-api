package com.tramo.backend.auth.service;

import com.tramo.backend.exception.LimitExceededException;
import com.tramo.backend.security.ratelimit.RateLimiterService;
import io.github.bucket4j.Bucket;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class IdentityRateLimiter {
    private final RateLimiterService rateLimiterService;

    public IdentityRateLimiter(RateLimiterService rateLimiterService) {
        this.rateLimiterService = rateLimiterService;
    }

    public void check(String scope, String identifier, int capacity, int refillTokens) {
        Bucket bucket = rateLimiterService.resolveBucket(
                scope + ":" + identifier.trim().toLowerCase(), capacity, refillTokens, Duration.ofMinutes(1));
        if (!bucket.tryConsume(1)) {
            throw new LimitExceededException("Too many attempts. Try again later.");
        }
    }
}
