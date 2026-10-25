// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.security;

import com.tramo.backend.security.ratelimit.RateLimitFilter;
import com.tramo.backend.security.ratelimit.RateLimiterService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ClientIpTest {
    private static final String KEY = "dGVzdC1iZmYtc2lnbmluZy1rZXktdGVzdC1iZmYta2V5";

    private MockHttpServletRequest signed(String ip) throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Tramo-Client-IP", ip);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Base64.getDecoder().decode(KEY), "HmacSHA256"));
        request.addHeader("X-Tramo-Signature", HexFormat.of().formatHex(
                mac.doFinal((ip + "\nPOST\n/api/auth/login").getBytes(StandardCharsets.UTF_8))));
        return request;
    }

    @Test
    void ignoresForwardedHeadersAndUnsignedClientIp() {
        var request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "1.2.3.4");
        request.addHeader("CF-Connecting-IP", "1.2.3.4");
        request.addHeader("X-Tramo-Client-IP", "1.2.3.4");
        assertThat(new ClientIp("").from(request)).isEqualTo("10.0.0.1");
        assertThat(new ClientIp(KEY).from(request)).isEqualTo("10.0.0.1");
        assertThat(new ClientIp(KEY).isTrusted(request)).isFalse();
    }

    @Test
    void acceptsSignedIpv4AndIpv6AndRejectsTampering() throws Exception {
        var clientIp = new ClientIp(KEY);
        for (String ip : new String[]{"1.2.3.4", "2001:db8::1"}) {
            var request = signed(ip);
            assertThat(clientIp.from(request)).isEqualTo(ip);
            request.setRequestURI("/api/auth/register");
            assertThat(clientIp.isTrusted(request)).isFalse();
        }
        var request = signed("1.2.3.4");
        request.removeHeader("X-Tramo-Client-IP");
        request.addHeader("X-Tramo-Client-IP", "5.6.7.8");
        assertThat(clientIp.isTrusted(request)).isFalse();
        request.removeHeader("X-Tramo-Signature");
        request.addHeader("X-Tramo-Signature", "z".repeat(64));
        assertThat(clientIp.isTrusted(request)).isFalse();
        assertThatThrownBy(() -> new ClientIp("c2hvcnQ=")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsSignatureProducedByTheNodeBff() {
        var request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.addHeader("X-Tramo-Client-IP", "1.2.3.4");
        request.addHeader("X-Tramo-Signature", "e36496881db5ace152845ee2aa32d4d3ea4663c701e53289938e271771f87afc");
        assertThat(new ClientIp(KEY).isTrusted(request)).isTrue();
    }

    @Test
    void unsignedBackendRequestsAreRejectedBeforeConsumingRateLimits() throws Exception {
        var chain = mock(FilterChain.class);
        var filter = new RateLimitFilter(new RateLimiterService(), new ClientIp(KEY));
        var response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST", "/api/auth/login"), response, chain);
        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(chain);
    }

    @Test
    void twoClientsBehindTheSameBffHaveIndependentLoginBuckets() throws Exception {
        var chain = mock(FilterChain.class);
        var filter = new RateLimitFilter(new RateLimiterService(), new ClientIp(KEY));
        var attacker = signed("1.2.3.4");
        for (int i = 0; i < 10; i++) {
            var response = new MockHttpServletResponse();
            filter.doFilter(attacker, response, chain);
            assertThat(response.getStatus()).isEqualTo(200);
        }
        var blocked = new MockHttpServletResponse();
        filter.doFilter(attacker, blocked, chain);
        assertThat(blocked.getStatus()).isEqualTo(429);
        var victim = signed("5.6.7.8");
        var allowed = new MockHttpServletResponse();
        filter.doFilter(victim, allowed, chain);
        assertThat(allowed.getStatus()).isEqualTo(200);
        verify(chain, times(10)).doFilter(org.mockito.ArgumentMatchers.eq(attacker), org.mockito.ArgumentMatchers.any());
        verify(chain).doFilter(victim, allowed);
    }

    @Test
    void onlyExternalCallbacksAndHealthCanSkipSigning() {
        var clientIp = new ClientIp(KEY);
        assertThat(clientIp.requiresSignature(new MockHttpServletRequest("GET", "/actuator/health"))).isFalse();
        assertThat(clientIp.requiresSignature(new MockHttpServletRequest("GET", "/api/auth/patreon/callback"))).isFalse();
        assertThat(clientIp.requiresSignature(new MockHttpServletRequest("POST", "/api/webhooks/patreon"))).isFalse();
        assertThat(clientIp.requiresSignature(new MockHttpServletRequest("GET", "/api/webhooks/other"))).isTrue();
        assertThat(clientIp.requiresSignature(new MockHttpServletRequest("POST", "/api/auth/patreon/callback"))).isTrue();
    }
}
