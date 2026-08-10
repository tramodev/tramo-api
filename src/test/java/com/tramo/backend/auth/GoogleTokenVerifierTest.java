package com.tramo.backend.auth;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.json.webtoken.JsonWebSignature;
import com.tramo.backend.auth.service.GoogleTokenVerifier;
import com.tramo.backend.exception.InvalidTokenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.security.GeneralSecurityException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GoogleTokenVerifierTest {

    private GoogleIdTokenVerifier delegate;
    private GoogleTokenVerifier googleTokenVerifier;

    @BeforeEach
    void setUp() {
        googleTokenVerifier = new GoogleTokenVerifier("test-client-id");
        delegate = mock(GoogleIdTokenVerifier.class);
        ReflectionTestUtils.setField(googleTokenVerifier, "verifier", delegate);
    }

    private GoogleIdToken tokenWith(Boolean emailVerified, String email, String name) {
        GoogleIdToken.Payload payload = new GoogleIdToken.Payload();
        payload.setEmailVerified(emailVerified);
        payload.setEmail(email);
        if (name != null) {
            payload.set("name", name);
        }
        JsonWebSignature.Header header = new JsonWebSignature.Header().setAlgorithm("RS256");
        return new GoogleIdToken(header, payload, new byte[]{1}, new byte[]{2});
    }

    @Test
    void returnsEmailAndNameForVerifiedToken() throws Exception {
        when(delegate.verify(anyString())).thenReturn(tokenWith(true, "someone@example.com", "Someone"));

        GoogleTokenVerifier.GoogleTokenPayload payload = googleTokenVerifier.verify("id-token");

        assertThat(payload.email()).isEqualTo("someone@example.com");
        assertThat(payload.name()).isEqualTo("Someone");
    }

    @Test
    void allowsMissingName() throws Exception {
        when(delegate.verify(anyString())).thenReturn(tokenWith(true, "someone@example.com", null));

        assertThat(googleTokenVerifier.verify("id-token").name()).isNull();
    }

    @Test
    void rejectsUnparseableToken() throws Exception {
        when(delegate.verify(anyString())).thenReturn(null);

        assertThatThrownBy(() -> googleTokenVerifier.verify("garbage"))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessage("Invalid Google token");
    }

    @Test
    void rejectsUnverifiedEmail() throws Exception {
        when(delegate.verify(anyString())).thenReturn(tokenWith(false, "someone@example.com", "Someone"));

        assertThatThrownBy(() -> googleTokenVerifier.verify("id-token"))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessage("Google account email is not verified");
    }

    @Test
    void rejectsMissingEmailVerifiedClaim() throws Exception {
        when(delegate.verify(anyString())).thenReturn(tokenWith(null, "someone@example.com", "Someone"));

        assertThatThrownBy(() -> googleTokenVerifier.verify("id-token"))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessage("Google account email is not verified");
    }

    @Test
    void wrapsSecurityFailure() throws Exception {
        when(delegate.verify(anyString())).thenThrow(new GeneralSecurityException("bad signature"));

        assertThatThrownBy(() -> googleTokenVerifier.verify("id-token"))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessage("Invalid Google token");
    }

    @Test
    void wrapsTransportFailure() throws Exception {
        when(delegate.verify(anyString())).thenThrow(new IOException("certs unreachable"));

        assertThatThrownBy(() -> googleTokenVerifier.verify("id-token"))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessage("Invalid Google token");
    }

    @Test
    void wrapsMalformedTokenArgument() throws Exception {
        when(delegate.verify(anyString())).thenThrow(new IllegalArgumentException("not a JWT"));

        assertThatThrownBy(() -> googleTokenVerifier.verify(""))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessage("Invalid Google token");
    }
}
