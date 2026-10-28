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
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
import static org.mockito.Mockito.doAnswer;
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

    @Autowired
    JavaMailSenderImpl mailSender;

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
                .with(uniqueIp())
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
    void changingPasswordInvalidatesAllOutstandingResetLinks() throws Exception {
        User user = createUser("changedreset");
        String first = issueResetToken(user, Instant.now().plus(1, ChronoUnit.HOURS));
        String second = issueResetToken(user, Instant.now().plus(1, ChronoUnit.HOURS));
        passwordService.changePassword(user, "Passw0rd123!", "ChangedPassw0rd!");
        resetPassword(first, "NewPassw0rd!").andExpect(status().isUnauthorized());
        resetPassword(second, "NewPassw0rd!").andExpect(status().isUnauthorized());
        passwordService.forgotPassword(user.getEmail());
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(emailService, org.mockito.Mockito.timeout(5000)).sendPasswordResetEmail(any(User.class), token.capture());
        resetPassword(token.getValue(), "NewPassw0rd!").andExpect(status().isNoContent());
        resetPassword(token.getValue(), "OtherPassw0rd!").andExpect(status().isUnauthorized());
    }

    @Test
    void resettingPasswordInvalidatesOtherOutstandingLinks() throws Exception {
        User user = createUser("allreset");
        String first = issueResetToken(user, Instant.now().plus(1, ChronoUnit.HOURS));
        String second = issueResetToken(user, Instant.now().plus(1, ChronoUnit.HOURS));
        resetPassword(first, "NewPassw0rd!").andExpect(status().isNoContent());
        resetPassword(second, "OtherPassw0rd!").andExpect(status().isUnauthorized());
    }

    @Test
    void concurrentResetAndAuthenticatedChangeCannotBothUseOldCredentials() throws Exception {
        User user = createUser("concurrentchange");
        String token = issueResetToken(user, Instant.now().plus(1, ChronoUnit.HOURS));
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var reset = executor.submit(() -> {
                start.await();
                try {
                    passwordService.resetPassword(token, "ResetPassw0rd!");
                    return true;
                } catch (InvalidTokenException rejected) {
                    return false;
                }
            });
            var change = executor.submit(() -> {
                start.await();
                try {
                    passwordService.changePassword(user, "Passw0rd123!", "ChangedPassw0rd!");
                    return true;
                } catch (com.tramo.backend.exception.RequestValidationException rejected) {
                    return false;
                }
            });
            start.countDown();
            assertThat(java.util.List.of(reset.get(10, TimeUnit.SECONDS), change.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(passwordResetTokenRepository.findAll()).isEmpty();
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentDistinctResetLinksCannotBothChangePassword() throws Exception {
        User user = createUser("concurrentlinks");
        String first = issueResetToken(user, Instant.now().plus(1, ChronoUnit.HOURS));
        String second = issueResetToken(user, Instant.now().plus(1, ChronoUnit.HOURS));
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        java.util.function.Function<String, java.util.concurrent.Callable<Boolean>> call = token -> () -> {
            start.await();
            try {
                passwordService.resetPassword(token, "NewPassw0rd!");
                return true;
            } catch (InvalidTokenException rejected) {
                return false;
            }
        };
        try {
            var one = executor.submit(call.apply(first));
            var two = executor.submit(call.apply(second));
            start.countDown();
            assertThat(java.util.List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void forgotPasswordCreatesTokenAndSendsEmail() throws Exception {
        User user = createUser("forgetful");
        var delivered = new java.util.concurrent.CompletableFuture<Void>();
        doAnswer(invocation -> {
            try {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                assertThat(TransactionSynchronizationManager.hasResource(entityManagerFactory)).isFalse();
                assertThat(passwordResetTokenRepository.findByTokenHash(OpaqueToken.hash(invocation.getArgument(1)))).isPresent();
                delivered.complete(null);
            } catch (Throwable failure) {
                delivered.completeExceptionally(failure);
            }
            return null;
        }).when(emailService).sendPasswordResetEmail(any(User.class), anyString());

        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"forgetful@example.com"}"""))
                .andExpect(status().isNoContent());

        delivered.get(5, TimeUnit.SECONDS);
        assertThat(passwordResetTokenRepository.findAll())
                .filteredOn(t -> t.getUser().getId().equals(user.getId()))
                .hasSize(1);
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(emailService, org.mockito.Mockito.timeout(5000)).sendPasswordResetEmail(any(User.class), token.capture());
        PasswordResetToken stored = passwordResetTokenRepository.findAll().get(0);
        assertThat(stored.getTokenHash()).isEqualTo(OpaqueToken.hash(token.getValue()))
                .isNotEqualTo(token.getValue());
        resetPassword(stored.getTokenHash(), "NewPassw0rd!").andExpect(status().isUnauthorized());
        resetPassword(token.getValue(), "NewPassw0rd!").andExpect(status().isNoContent());
    }

    @Test
    void smtpOperationsHaveFiniteTimeouts() {
        assertThat(mailSender.getJavaMailProperties())
                .containsEntry("mail.smtp.connectiontimeout", "5000")
                .containsEntry("mail.smtp.timeout", "5000")
                .containsEntry("mail.smtp.writetimeout", "5000");
    }

    @Test
    void forgotPasswordIsSilentForUnknownEmail() throws Exception {
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"nobody@example.com"}"""))
                .andExpect(status().isNoContent());
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

        verify(emailService, org.mockito.Mockito.timeout(5000)).sendPasswordResetEmail(any(User.class), anyString());
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
    void resetRejectsCurrentPasswordWithoutConsumingLinkOrRevokingSessions() throws Exception {
        User user = createUser("unchangedreset");
        String loginBody = login(user.getUsername(), "Passw0rd123!").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String accessToken = com.jayway.jsonpath.JsonPath.read(loginBody, "$.accessToken");
        String refreshToken = com.jayway.jsonpath.JsonPath.read(loginBody, "$.refreshToken");
        String token = issueResetToken(user, Instant.now().plus(1, ChronoUnit.HOURS));
        String previousHash = user.getPassword();

        resetPassword(token, "Passw0rd123!").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NEW_PASSWORD_UNCHANGED"));

        User current = userRepository.findById(user.getId()).orElseThrow();
        assertThat(current.getPassword()).isEqualTo(previousHash);
        assertThat(current.getSessionVersion()).isEqualTo(user.getSessionVersion());
        assertThat(passwordResetTokenRepository.findByTokenHash(OpaqueToken.hash(token))).isPresent();
        assertThat(refreshTokenRepository.findByTokenHash(OpaqueToken.hash(refreshToken))).isPresent();
        mockMvc.perform(get("/api/profile/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());
        resetPassword(token, "NewPassw0rd!").andExpect(status().isNoContent());
        login(user.getUsername(), "Passw0rd123!").andExpect(status().isUnauthorized());
        login(user.getUsername(), "NewPassw0rd!").andExpect(status().isOk());
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
