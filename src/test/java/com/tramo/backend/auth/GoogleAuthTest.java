// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.auth;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.auth.service.GoogleTokenVerifier;
import com.tramo.backend.exception.InvalidTokenException;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GoogleAuthTest extends AbstractIntegrationTest {

    private void stubGoogleToken(String email, String name) {
        when(googleTokenVerifier.verify(anyString()))
                .thenReturn(new GoogleTokenVerifier.GoogleTokenPayload("google-" + email, email, name));
    }

    private org.springframework.test.web.servlet.ResultActions googleAuth() throws Exception {
        return mockMvc.perform(post("/api/auth/google")
                .with(uniqueIp())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"idToken":"fake-token"}"""));
    }

    private org.springframework.test.web.servlet.ResultActions linkGoogle(String password) throws Exception {
        return mockMvc.perform(post("/api/auth/google")
                .with(uniqueIp())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"idToken":"fake-token","password":"%s"}""".formatted(password)));
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
        assertThat(created.getGoogleSub()).isEqualTo("google-newperson@gmail.com");
    }

    @Test
    void generatesFallbackUsernameWhenLocalPartIsTooShort() throws Exception {
        stubGoogleToken("ab@gmail.com", "Ab");

        
        
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
    void neverVerifiesAnExistingAccountOrEnablesItsPasswordOnGoogleLogin() throws Exception {
        User user = createUser("halfway", "halfway@example.com", false, false, com.tramo.backend.user.Role.USER);
        stubGoogleToken("halfway@example.com", "Halfway");

        googleAuth()
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GOOGLE_LINK_VERIFICATION_REQUIRED"));

        User existing = userRepository.findById(user.getId()).orElseThrow();
        assertThat(existing.isEmailVerified()).isFalse();
        assertThat(existing.getGoogleSub()).isNull();
    }

    @Test
    void existingVerifiedAccountRequiresExplicitLinking() throws Exception {
        createUser("already", "already@example.com", true, false, com.tramo.backend.user.Role.USER);
        stubGoogleToken("already@example.com", "Already");

        googleAuth()
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GOOGLE_LINK_REQUIRED"));

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

    @Test
    void linksOnlyAfterCheckingTheExistingPassword() throws Exception {
        User user = createUser("linkme");
        stubGoogleToken(user.getEmail(), "Link Me");

        linkGoogle("WrongPassword123!").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CURRENT_PASSWORD_INCORRECT"));
        assertThat(userRepository.findById(user.getId()).orElseThrow().getGoogleSub()).isNull();

        linkGoogle("Passw0rd123!").andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("linkme"));
        assertThat(userRepository.findById(user.getId()).orElseThrow().getGoogleSub())
                .isEqualTo("google-" + user.getEmail());

        googleAuth().andExpect(status().isOk());
    }

    @Test
    void subsequentLoginsUseSubjectEvenIfGoogleEmailChanges() throws Exception {
        stubGoogleToken("original@gmail.com", "Original");
        googleAuth().andExpect(status().isOk());
        when(googleTokenVerifier.verify(anyString())).thenReturn(
                new GoogleTokenVerifier.GoogleTokenPayload("google-original@gmail.com", "changed@gmail.com", "Original"));

        googleAuth().andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("original"));
        assertThat(userRepository.findAll()).hasSize(1);
    }

    @Test
    void matchingEmailCannotReplaceALinkedSubject() throws Exception {
        stubGoogleToken("linked@gmail.com", "Linked");
        googleAuth().andExpect(status().isOk());
        when(googleTokenVerifier.verify(anyString())).thenReturn(
                new GoogleTokenVerifier.GoogleTokenPayload("another-subject", "linked@gmail.com", "Other"));

        linkGoogle("Passw0rd123!").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GOOGLE_ALREADY_LINKED"));
        assertThat(userRepository.findByEmail("linked@gmail.com").orElseThrow().getGoogleSub())
                .isEqualTo("google-linked@gmail.com");
    }

    @Test
    void unverifiedAccountCannotBeLinkedEvenWithItsPassword() throws Exception {
        User user = createUser("unverifiedlink", "unverifiedlink@gmail.com", false, false, com.tramo.backend.user.Role.USER);
        stubGoogleToken(user.getEmail(), "Unverified");

        linkGoogle("Passw0rd123!").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GOOGLE_LINK_VERIFICATION_REQUIRED"));
        assertThat(userRepository.findById(user.getId()).orElseThrow().isEmailVerified()).isFalse();
    }

    @Test
    void legacyGoogleOnlyAccountCanRecoverAndLinkWithoutLosingData() throws Exception {
        User user = createUser("legacygoogle");
        user.setPassword(null);
        userRepository.save(user);
        stubGoogleToken(user.getEmail(), "Legacy");
        googleAuth().andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GOOGLE_LINK_REQUIRED"));

        mockMvc.perform(post("/api/auth/forgot-password").with(uniqueIp())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s"}""".formatted(user.getEmail())))
                .andExpect(status().isNoContent());
        ArgumentCaptor<String> resetToken = ArgumentCaptor.forClass(String.class);
        verify(emailService, org.mockito.Mockito.timeout(5000)).sendPasswordResetEmail(any(User.class), resetToken.capture());
        String token = resetToken.getValue();
        mockMvc.perform(post("/api/auth/reset-password").with(uniqueIp())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"%s","newPassword":"RecoveredPass123!"}""".formatted(token)))
                .andExpect(status().isNoContent());

        linkGoogle("RecoveredPass123!").andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("legacygoogle"));
        assertThat(userRepository.findByGoogleSub("google-" + user.getEmail()).orElseThrow().getId())
                .isEqualTo(user.getId());
        assertThat(userRepository.findAll()).hasSize(1);
    }
}
