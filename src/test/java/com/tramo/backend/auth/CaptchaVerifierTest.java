package com.tramo.backend.auth;

import com.tramo.backend.auth.service.CaptchaVerifier;
import com.tramo.backend.exception.CaptchaVerificationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CaptchaVerifierTest {

    private static final String URL = "https://www.google.com/recaptcha/api/siteverify";

    private CaptchaVerifier captchaVerifier;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        captchaVerifier = new CaptchaVerifier();
        ReflectionTestUtils.setField(captchaVerifier, "secretKey", "shhh");
        ReflectionTestUtils.setField(captchaVerifier, "minScore", 0.5);

        RestClient.Builder builder = RestClient.builder().baseUrl("https://www.google.com");
        server = MockRestServiceServer.bindTo(builder).build();
        ReflectionTestUtils.setField(captchaVerifier, "restClient", builder.build());
    }

    private void respondWith(String body) {
        server.expect(requestTo(URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    @Test
    void acceptsScoreAboveThresholdAndPostsSecretAndToken() {
        server.expect(requestTo(URL))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("secret=shhh")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("response=tok")))
                .andRespond(withSuccess("""
                        {"success":true,"score":0.9,"action":"register"}""", MediaType.APPLICATION_JSON));

        assertThatCode(() -> captchaVerifier.verify("tok", "register")).doesNotThrowAnyException();
        server.verify();
    }

    @Test
    void acceptsScoreExactlyAtThreshold() {
        respondWith("""
                {"success":true,"score":0.5,"action":"register"}""");

        assertThatCode(() -> captchaVerifier.verify("tok", "register")).doesNotThrowAnyException();
    }

    @Test
    void rejectsScoreBelowThreshold() {
        respondWith("""
                {"success":true,"score":0.4,"action":"register"}""");

        assertThatThrownBy(() -> captchaVerifier.verify("tok", "register"))
                .isInstanceOf(CaptchaVerificationException.class);
    }

    @Test
    void rejectsUnsuccessfulVerification() {
        respondWith("""
                {"success":false,"score":0.9,"action":"register"}""");

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
    void rejectsWhenProviderReturnsError() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> captchaVerifier.verify("tok", "register"))
                .isInstanceOf(CaptchaVerificationException.class);
    }
}
