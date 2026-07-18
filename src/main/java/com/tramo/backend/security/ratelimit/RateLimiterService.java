package com.tramo.backend.security.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.*;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class RateLimiterService {

    private final Cache<String, Bucket> cache = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(10))
            .build();

    public Bucket resolveBucket(String key, int capacity, int refillTokens, Duration refillDuration) {

        return cache.get(key, k ->
                Bucket.builder()
                        .addLimit(Bandwidth.classic(
                                capacity,
                                Refill.intervally(refillTokens, refillDuration)
                        ))
                        .build()
        );
    }
}
