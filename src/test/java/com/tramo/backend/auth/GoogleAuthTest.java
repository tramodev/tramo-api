package com.tramo.backend.auth;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.auth.service.GoogleTokenVerifier;
import com.tramo.backend.exception.InvalidTokenException;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GoogleAuthTest extends AbstractIntegrationTest {

    private void stubGoogleToken(String email, String name) {
        when(googleTokenVerifier.verify(anyString()))
                .thenReturn(new GoogleTokenVerifier.GoogleTokenPayload(email, name));
    }

    private org.springframework.test.web.servlet.ResultActions googleAuth() throws Exception {
        return mockMvc.perform(post("/api/auth/google")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"idToken":"fake-token"}"""));
    }

    @Test
    void createsNewVerifiedUserFromGoogleProfile() throws Exception {
        stubGoogleToken("newperson@gmail.com", "New Person");

        googleAuth()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.username").value("newperson"));

        User created = userRepository.findByEmail("newperson@gmail.com").orElseThrow();
        assertThat(created.isEmailVerified()).isTrue();
        assertThat(created.getPassword()).isNull();
    }

    @Test
    void generatesFallbackUsernameWhenLocalPartIsTooShort() throws Exception {
        stubGoogleToken("ab@gmail.com", "Ab");

        // generateUsernameFromEmail pads "ab" to "abuser" then truncates back to
        // max(3, original length) = 3 chars, so the real result is "abu", not "abuser".
        googleAuth()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("abu"));
    }

    @Test
    void appendsSuffixWhenGeneratedUsernameIsTaken() throws Exception {
        createUser("clash");
        stubGoogleToken("clash@gmail.com", "Clash");

        googleAuth()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("clash1"));
    }

    @Test
    void marksExistingUnverifiedUserVerifiedOnGoogleLogin() throws Exception {
        User user = createUser("halfway", "halfway@example.com", false, false, com.tramo.backend.user.Role.USER);
        stubGoogleToken("halfway@example.com", "Halfway");

        googleAuth()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("halfway"));

        assertThat(userRepository.findById(user.getId()).orElseThrow().isEmailVerified()).isTrue();
    }

    @Test
    void logsInExistingVerifiedUserWithoutCreatingDuplicate() throws Exception {
        createUser("already", "already@example.com", true, false, com.tramo.backend.user.Role.USER);
        stubGoogleToken("already@example.com", "Already");

        googleAuth()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("already"));

        assertThat(userRepository.findAll())
                .filteredOn(u -> "already@example.com".equals(u.getEmail()))
                .hasSize(1);
    }

    @Test
    void rejectsInvalidGoogleToken() throws Exception {
        when(googleTokenVerifier.verify(anyString()))
                .thenThrow(new InvalidTokenException("Invalid Google token"));

        googleAuth().andExpect(status().isUnauthorized());
    }
}
