// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.auth;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.auth.entity.PasswordResetToken;
import com.tramo.backend.auth.entity.RefreshToken;
import com.tramo.backend.auth.repository.PasswordResetTokenRepository;
import com.tramo.backend.auth.repository.RefreshTokenRepository;
import com.tramo.backend.auth.service.OpaqueToken;
import com.tramo.backend.auth.service.PasswordService;
import com.tramo.backend.exception.InvalidTokenException;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PasswordResetTest extends AbstractIntegrationTest {

    @Autowired
    PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    RefreshTokenRepository refreshTokenRepository;

    @Autowired
    PasswordService passwordService;

    private String issueResetToken(User user, Instant expiresAt) {
        String rawToken = OpaqueToken.generate();
        PasswordResetToken token = new PasswordResetToken();
        token.setUser(user);
        token.setTokenHash(OpaqueToken.hash(rawToken));
        token.setExpiresAt(expiresAt);
        passwordResetTokenRepository.save(token);
        return rawToken;
    }

    private ResultActions resetPassword(String token, String newPassword) throws Exception {
        return mockMvc.perform(post("/api/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"token":"%s","newPassword":"%s"}""".formatted(token, newPassword)));
    }

    private ResultActions login(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .with(uniqueIp())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"username":"%s","password":"%s"}""".formatted(username, password)));
    }

    @Test
    void forgotPasswordCreatesTokenAndSendsEmail() throws Exception {
        User user = createUser("forgetful");

        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"forgetful@example.com"}"""))
                .andExpect(status().isNoContent());

        assertThat(passwordResetTokenRepository.findAll())
                .filteredOn(t -> t.getUser().getId().equals(user.getId()))
                .hasSize(1);
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendPasswordResetEmail(any(User.class), token.capture());
        PasswordResetToken stored = passwordResetTokenRepository.findAll().get(0);
        assertThat(stored.getTokenHash()).isEqualTo(OpaqueToken.hash(token.getValue()))
                .isNotEqualTo(token.getValue());
        resetPassword(stored.getTokenHash(), "NewPassw0rd!").andExpect(status().isUnauthorized());
        resetPassword(token.getValue(), "NewPassw0rd!").andExpect(status().isNoContent());
    }

    @Test
    void forgotPasswordIsSilentForUnknownEmail() throws Exception {
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"nobody@example.com"}"""))
                .andExpect(status().isNoContent());

        assertThat(passwordResetTokenRepository.findAll()).isEmpty();
        verify(emailService, never()).sendPasswordResetEmail(any(), anyString());
    }

    @Test
    void forgotPasswordReplacesPreviousToken() throws Exception {
        User user = createUser("repeat");
        issueResetToken(user, Instant.now().plus(1, ChronoUnit.HOURS));

        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"repeat@example.com"}"""))
                .andExpect(status().isNoContent());

        assertThat(passwordResetTokenRepository.findAll())
                .filteredOn(t -> t.getUser().getId().equals(user.getId()))
                .hasSize(1);
    }

    @Test
    void resetPasswordChangesPasswordAndRevokesAccessAndRefreshTokens() throws Exception {
        User user = createUser("resetter");
        String body = login("resetter", "Passw0rd123!").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String accessToken = com.jayway.jsonpath.JsonPath.read(body, "$.accessToken");
        assertThat(refreshTokenRepository.findAll())
                .filteredOn(t -> t.getUser().getId().equals(user.getId()))
                .isNotEmpty();

        String token = issueResetToken(user, Instant.now().plus(1, ChronoUnit.HOURS));
        resetPassword(token, "NewPassw0rd!").andExpect(status().isNoContent());

        mockMvc.perform(get("/api/profile/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());

        login("resetter", "Passw0rd123!").andExpect(status().isUnauthorized());
        String newBody = login("resetter", "NewPassw0rd!").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String newAccessToken = com.jayway.jsonpath.JsonPath.read(newBody, "$.accessToken");
        mockMvc.perform(get("/api/profile/me").header("Authorization", "Bearer " + newAccessToken))
                .andExpect(status().isOk());
        assertThat(refreshTokenRepository.findAll())
                .filteredOn((RefreshToken t) -> t.getUser().getId().equals(user.getId()))
                .hasSize(1);
        assertThat(passwordResetTokenRepository.findByTokenHash(OpaqueToken.hash(token))).isEmpty();
    }

    @Test
    void resetPasswordRejectsUnknownToken() throws Exception {
        resetPassword("bogus-token", "NewPassw0rd!")
                .andExpect(status().isUnauthorized());
    }

    @Test
    void resetPasswordRejectsExpiredTokenAndDeletesIt() throws Exception {
        User user = createUser("slowpoke");
        String token = issueResetToken(user, Instant.now().minus(1, ChronoUnit.MINUTES));

        resetPassword(token, "NewPassw0rd!")
                .andExpect(status().isUnauthorized());

        assertThat(passwordResetTokenRepository.findByTokenHash(OpaqueToken.hash(token))).isEmpty();
        login("slowpoke", "Passw0rd123!").andExpect(status().isOk());
    }

    @Test
    void resetPasswordTokenIsSingleUse() throws Exception {
        User user = createUser("oneshot");
        String token = issueResetToken(user, Instant.now().plus(1, ChronoUnit.HOURS));

        resetPassword(token, "NewPassw0rd!").andExpect(status().isNoContent());
        resetPassword(token, "OtherPassw0rd!").andExpect(status().isUnauthorized());
    }

    @Test
    void concurrentResetsConsumeTokenOnlyOnce() throws Exception {
        User user = createUser("concurrentreset");
        String token = issueResetToken(user, Instant.now().plus(1, ChronoUnit.HOURS));
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        java.util.concurrent.Callable<Boolean> call = () -> {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
            try {
                passwordService.resetPassword(token, "NewPassw0rd!");
                return true;
            } catch (InvalidTokenException ex) {
                return false;
            }
        };
        try {
            var first = executor.submit(call);
            var second = executor.submit(call);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(java.util.List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(passwordResetTokenRepository.findAll()).isEmpty();
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void resetPasswordEnforcesPasswordComplexity() throws Exception {
        User user = createUser("weakling");
        String token = issueResetToken(user, Instant.now().plus(1, ChronoUnit.HOURS));

        resetPassword(token, "weakpass")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.newPassword").exists());
    }
}
