package com.tramo.backend.auth.service;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.tramo.backend.exception.InvalidTokenException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Collections;

@Component
public class GoogleTokenVerifier {
    private final GoogleIdTokenVerifier verifier;

    public GoogleTokenVerifier(@Value("${app.google.client-id}") String clientId) {
        // Verifies the signature locally against Google's public certs, which the
        // underlying HTTP transport fetches once and caches in memory per their
        // Cache-Control headers - no per-login network call to Google, unlike the
        // old /tokeninfo-based approach.
        this.verifier = new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance())
                .setAudience(Collections.singletonList(clientId))
                .build();
    }

    public record GoogleTokenPayload(String email, String name) {
    }

    public GoogleTokenPayload verify(String idToken) {
        GoogleIdToken token;
        try {
            token = verifier.verify(idToken);
        } catch (GeneralSecurityException | IOException | IllegalArgumentException ex) {
            throw new InvalidTokenException("Invalid Google token");
        }

        if (token == null) {
            throw new InvalidTokenException("Invalid Google token");
        }

        GoogleIdToken.Payload payload = token.getPayload();
        if (!Boolean.TRUE.equals(payload.getEmailVerified())) {
            throw new InvalidTokenException("Google account email is not verified");
        }

        return new GoogleTokenPayload(payload.getEmail(), (String) payload.get("name"));
    }
}
