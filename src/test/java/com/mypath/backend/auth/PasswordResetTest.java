package com.mypath.backend.auth;

import com.mypath.backend.AbstractIntegrationTest;
import com.mypath.backend.auth.entity.PasswordResetToken;
import com.mypath.backend.auth.entity.RefreshToken;
import com.mypath.backend.auth.repository.PasswordResetTokenRepository;
import com.mypath.backend.auth.repository.RefreshTokenRepository;
import com.mypath.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PasswordResetTest extends AbstractIntegrationTest {

    @Autowired
    PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    RefreshTokenRepository refreshTokenRepository;

    private PasswordResetToken issueResetToken(User user, Instant expiresAt) {
        PasswordResetToken token = new PasswordResetToken();
        token.setUser(user);
        token.setToken(UUID.randomUUID().toString());
        token.setExpiresAt(expiresAt);
        return passwordResetTokenRepository.save(token);
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
        verify(emailService).sendPasswordResetEmail(any(User.class), anyString());
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
    void resetPasswordChangesPasswordAndRevokesRefreshTokens() throws Exception {
        User user = createUser("resetter");
        login("resetter", "Passw0rd!").andExpect(status().isOk());
        assertThat(refreshTokenRepository.findAll())
                .filteredOn(t -> t.getUser().getId().equals(user.getId()))
                .isNotEmpty();

        PasswordResetToken token = issueResetToken(user, Instant.now().plus(1, ChronoUnit.HOURS));
        resetPassword(token.getToken(), "NewPassw0rd!").andExpect(status().isNoContent());

        login("resetter", "Passw0rd!").andExpect(status().isUnauthorized());
        login("resetter", "NewPassw0rd!").andExpect(status().isOk());
        assertThat(refreshTokenRepository.findAll())
                .filteredOn((RefreshToken t) -> t.getUser().getId().equals(user.getId()))
                .hasSize(1);
        assertThat(passwordResetTokenRepository.findByToken(token.getToken())).isEmpty();
    }

    @Test
    void resetPasswordRejectsUnknownToken() throws Exception {
        resetPassword("bogus-token", "NewPassw0rd!")
                .andExpect(status().isUnauthorized());
    }

    @Test
    void resetPasswordRejectsExpiredTokenAndDeletesIt() throws Exception {
        User user = createUser("slowpoke");
        PasswordResetToken token = issueResetToken(user, Instant.now().minus(1, ChronoUnit.MINUTES));

        resetPassword(token.getToken(), "NewPassw0rd!")
                .andExpect(status().isUnauthorized());

        assertThat(passwordResetTokenRepository.findByToken(token.getToken())).isEmpty();
        login("slowpoke", "Passw0rd!").andExpect(status().isOk());
    }

    @Test
    void resetPasswordTokenIsSingleUse() throws Exception {
        User user = createUser("oneshot");
        PasswordResetToken token = issueResetToken(user, Instant.now().plus(1, ChronoUnit.HOURS));

        resetPassword(token.getToken(), "NewPassw0rd!").andExpect(status().isNoContent());
        resetPassword(token.getToken(), "OtherPassw0rd!").andExpect(status().isUnauthorized());
    }

    @Test
    void resetPasswordEnforcesPasswordComplexity() throws Exception {
        User user = createUser("weakling");
        PasswordResetToken token = issueResetToken(user, Instant.now().plus(1, ChronoUnit.HOURS));

        resetPassword(token.getToken(), "weakpass")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.newPassword").exists());
    }
}
