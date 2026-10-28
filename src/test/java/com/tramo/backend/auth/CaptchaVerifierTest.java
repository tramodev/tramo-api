// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.auth;

import com.sun.net.httpserver.HttpServer;
import com.tramo.backend.auth.service.CaptchaVerifier;
import com.tramo.backend.exception.CaptchaVerificationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CaptchaVerifierTest {

    private HttpServer server;
    private CaptchaVerifier captchaVerifier;
    private String responseBody;
    private int responseStatus;
    private String requestPath;
    private String requestContentType;
    private String requestBody;

    @BeforeEach
    void setUp() throws IOException {
        responseBody = "{}";
        responseStatus = 200;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/recaptcha/api/siteverify", exchange -> {
            requestPath = exchange.getRequestURI().getPath();
            requestContentType = exchange.getRequestHeaders().getFirst("Content-Type");
            requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        captchaVerifier = new CaptchaVerifier("http://127.0.0.1:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(captchaVerifier, "secretKey", "shhh");
        ReflectionTestUtils.setField(captchaVerifier, "minScore", 0.5);
        ReflectionTestUtils.setField(captchaVerifier, "frontendUrl", "https://tramo.test");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void respondWith(String body) {
        responseBody = body;
        responseStatus = 200;
    }

    @Test
    void acceptsScoreAboveThresholdAndPostsSecretAndToken() {
        respondWith("""
                {"success":true,"score":0.9,"action":"register","hostname":"tramo.test"}""");

        assertThatCode(() -> captchaVerifier.verify("tok", "register")).doesNotThrowAnyException();
        assertThat(requestPath).isEqualTo("/recaptcha/api/siteverify");
        assertThat(requestContentType).startsWith("application/x-www-form-urlencoded");
        assertThat(requestBody).contains("secret=shhh").contains("response=tok");
    }

    @Test
    void acceptsScoreExactlyAtThreshold() {
        respondWith("""
                {"success":true,"score":0.5,"action":"register","hostname":"tramo.test"}""");

        assertThatCode(() -> captchaVerifier.verify("tok", "register")).doesNotThrowAnyException();
    }

    @Test
    void rejectsScoreBelowThreshold() {
        respondWith("""
                {"success":true,"score":0.4,"action":"register","hostname":"tramo.test"}""");

        assertThatThrownBy(() -> captchaVerifier.verify("tok", "register"))
                .isInstanceOf(CaptchaVerificationException.class);
    }

    @Test
    void rejectsUnsuccessfulVerification() {
        respondWith("""
                {"success":false,"score":0.9,"action":"register","hostname":"tramo.test"}""");

        assertThatThrownBy(() -> captchaVerifier.verify("tok", "register"))
                .isInstanceOf(CaptchaVerificationException.class);
    }

    @Test
    void rejectsActionMismatch() {
        respondWith("""
                {"success":true,"score":0.9,"action":"login"}""");

        assertThatThrownBy(() -> captchaVerifier.verify("tok", "register"))
                .isInstanceOf(CaptchaVerificationException.class);
    }

    @Test
    void rejectsUnexpectedOrMissingHostname() {
        for (String hostname : new String[]{"evil.test", "sub.tramo.test", ""}) {
            respondWith("""
                    {"success":true,"score":0.9,"action":"register","hostname":"%s"}""".formatted(hostname));
            assertThatThrownBy(() -> captchaVerifier.verify("tok", "register"))
                    .isInstanceOf(CaptchaVerificationException.class);
        }
    }

    @Test
    void rejectsWhenProviderReturnsError() {
        responseBody = "{}";
        responseStatus = HttpStatus.SERVICE_UNAVAILABLE.value();

        assertThatThrownBy(() -> captchaVerifier.verify("tok", "register"))
                .isInstanceOf(CaptchaVerificationException.class);
    }
}
