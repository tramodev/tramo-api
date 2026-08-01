package com.tramo.backend.auth.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tramo.backend.exception.CaptchaVerificationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class CaptchaVerifier {
    private final RestClient restClient = RestClient.create("https://www.google.com");

    @Value("${app.captcha.secret-key}")
    private String secretKey;

    @Value("${app.captcha.min-score}")
    private double minScore;

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SiteVerifyResponse(boolean success, double score, String action) {
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
                || !expectedAction.equals(response.action())) {
            throw new CaptchaVerificationException("Captcha verification failed");
        }
    }
}
