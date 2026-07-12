package com.mypath.backend.auth.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mypath.backend.exception.InvalidTokenException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class GoogleTokenVerifier {
    private final RestClient restClient = RestClient.create("https://oauth2.googleapis.com");

    @Value("${app.google.client-id}")
    private String clientId;

    public record GoogleTokenPayload(String email, String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenInfoResponse(
            String aud,
            String email,
            @JsonProperty("email_verified") String emailVerified,
            String name
    ) {
    }

    public GoogleTokenPayload verify(String idToken) {
        TokenInfoResponse response;
        try {
            response = restClient.get()
                    .uri("/tokeninfo?id_token={token}", idToken)
                    .retrieve()
                    .body(TokenInfoResponse.class);
        } catch (RestClientException ex) {
            // Google's tokeninfo endpoint returns 4xx for any malformed/expired/tampered
            // token, so any client error here means the token itself is bad.
            throw new InvalidTokenException("Invalid Google token");
        }

        if (response == null || !clientId.equals(response.aud())) {
            throw new InvalidTokenException("Invalid Google token");
        }
        if (!"true".equals(response.emailVerified())) {
            throw new InvalidTokenException("Google account email is not verified");
        }

        return new GoogleTokenPayload(response.email(), response.name());
    }
}
