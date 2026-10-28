// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.auth.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tramo.backend.exception.CaptchaVerificationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;

@Component
public class CaptchaVerifier {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final RestClient restClient;

    public CaptchaVerifier(@Value("${app.captcha.base-url}") String baseUrl) {
        restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(timeoutFactory())
                .build();
    }

    private static SimpleClientHttpRequestFactory timeoutFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }

    @Value("${app.captcha.secret-key}")
    private String secretKey;

    @Value("${app.captcha.min-score}")
    private double minScore;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SiteVerifyResponse(boolean success, double score, String action, String hostname) {
    }

    public void verify(String token, String expectedAction) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("secret", secretKey);
        form.add("response", token);

        SiteVerifyResponse response;
        try {
            response = restClient.post()
                    .uri("/recaptcha/api/siteverify")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(SiteVerifyResponse.class);
        } catch (RestClientException ex) {
            throw new CaptchaVerificationException("Captcha verification failed");
        }

        if (response == null || !response.success()
                || response.score() < minScore
                || !expectedAction.equals(response.action())
                || !java.net.URI.create(frontendUrl).getHost().equalsIgnoreCase(response.hostname())) {
            throw new CaptchaVerificationException("Captcha verification failed");
        }
    }
}
