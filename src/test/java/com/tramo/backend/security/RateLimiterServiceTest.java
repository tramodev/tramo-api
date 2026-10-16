package com.tramo.backend.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.tramo.backend.security.ratelimit.RateLimiterService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterServiceTest {
    @Test
    void cacheRetainsBucketsForAtLeastTheHourlyUploadRefillPeriod() {
        var limiter = new RateLimiterService();
        var refillPeriod = Duration.ofHours(1);
        var bucket = limiter.resolveBucket("upload-bytes:1", 200, 200, refillPeriod);
        assertThat(bucket.tryConsume(200)).isTrue();

        var cache = (Cache<?, ?>) ReflectionTestUtils.getField(limiter, "cache");

        assertThat(cache.policy().expireAfterAccess().orElseThrow().getExpiresAfter()).isGreaterThanOrEqualTo(refillPeriod);
        assertThat(limiter.resolveBucket("upload-bytes:1", 200, 200, refillPeriod)).isSameAs(bucket);
        assertThat(bucket.tryConsume(1)).isFalse();
    }
}
