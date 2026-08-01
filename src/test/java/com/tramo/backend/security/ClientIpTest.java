package com.tramo.backend.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpTest {

    @Test
    void ignoresXForwardedForByDefault() {
        ClientIp clientIp = new ClientIp("");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "1.2.3.4");

        assertThat(clientIp.from(request)).isEqualTo("10.0.0.1");
    }

    @Test
    void fallsBackToRemoteAddrWhenTrustedHeaderMissing() {
        ClientIp clientIp = new ClientIp("CF-Connecting-IP");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");

        assertThat(clientIp.from(request)).isEqualTo("10.0.0.1");
    }

    @Test
    void usesTrustedHeaderWhenConfigured() {
        ClientIp clientIp = new ClientIp("CF-Connecting-IP");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("CF-Connecting-IP", "5.6.7.8");
        request.addHeader("X-Forwarded-For", "1.2.3.4");

        assertThat(clientIp.from(request)).isEqualTo("5.6.7.8");
    }
}
