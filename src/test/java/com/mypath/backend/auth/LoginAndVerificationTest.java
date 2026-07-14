package com.mypath.backend.auth;

import com.mypath.backend.AbstractIntegrationTest;
import com.mypath.backend.auth.entity.EmailVerificationToken;
import com.mypath.backend.auth.repository.EmailVerificationTokenRepository;
import com.mypath.backend.user.Role;
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

class LoginAndVerificationTest extends AbstractIntegrationTest {

    @Autowired
    EmailVerificationTokenRepository emailVerificationTokenRepository;

    private ResultActions login(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .with(uniqueIp())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"username":"%s","password":"%s"}""".formatted(username, password)));
    }

    private EmailVerificationToken issueVerificationToken(User user, Instant expiresAt) {
        EmailVerificationToken token = new EmailVerificationToken();
        token.setUser(user);
        token.setToken(UUID.randomUUID().toString());
        token.setExpiresAt(expiresAt);
        return emailVerificationTokenRepository.save(token);
    }

    @Test
    void loginReturnsTokensForVerifiedUser() throws Exception {
        createUser("alice");
        login("alice", "Passw0rd!")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.username").value("alice"));
    }

    @Test
    void loginIsCaseInsensitiveOnUsername() throws Exception {
        createUser("bob");
        login("BOB", "Passw0rd!")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("bob"));
    }

    @Test
    void loginRejectsWrongPassword() throws Exception {
        createUser("carol");
        login("carol", "WrongPass1!")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid username or password"));
    }

    @Test
    void loginRejectsUnknownUser() throws Exception {
        login("ghost", "Passw0rd!")
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginRejectsUnverifiedUser() throws Exception {
        createUser("pending", "pending@example.com", false, false, Role.USER);
        login("pending", "Passw0rd!")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Please verify your email before logging in."));
    }

    @Test
    void loginRejectsBannedUser() throws Exception {
        createUser("outlaw", "outlaw@example.com", true, true, Role.USER);
        login("outlaw", "Passw0rd!")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("This account has been banned."));
    }

    @Test
    void verifyEmailActivatesAccountAndReturnsTokens() throws Exception {
        User user = createUser("fresh", "fresh@example.com", false, false, Role.USER);
        EmailVerificationToken token = issueVerificationToken(user, Instant.now().plus(24, ChronoUnit.HOURS));

        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"%s"}""".formatted(token.getToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.username").value("fresh"));

        assertThat(userRepository.findById(user.getId()).orElseThrow().isEmailVerified()).isTrue();
        assertThat(emailVerificationTokenRepository.findByToken(token.getToken())).isEmpty();
    }

    @Test
    void verifyEmailRejectsUnknownToken() throws Exception {
        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"nonexistent-token"}"""))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void verifyEmailRejectsExpiredTokenAndDeletesIt() throws Exception {
        User user = createUser("late", "late@example.com", false, false, Role.USER);
        EmailVerificationToken token = issueVerificationToken(user, Instant.now().minus(1, ChronoUnit.HOURS));

        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"%s"}""".formatted(token.getToken())))
                .andExpect(status().isUnauthorized());

        assertThat(emailVerificationTokenRepository.findByToken(token.getToken())).isEmpty();
        assertThat(userRepository.findById(user.getId()).orElseThrow().isEmailVerified()).isFalse();
    }

    @Test
    void verifyEmailTokenIsSingleUse() throws Exception {
        User user = createUser("once", "once@example.com", false, false, Role.USER);
        EmailVerificationToken token = issueVerificationToken(user, Instant.now().plus(24, ChronoUnit.HOURS));
        String body = """
                {"token":"%s"}""".formatted(token.getToken());

        mockMvc.perform(post("/api/auth/verify-email").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/verify-email").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void resendVerificationReplacesTokenForUnverifiedUser() throws Exception {
        User user = createUser("resendme", "resendme@example.com", false, false, Role.USER);
        EmailVerificationToken old = issueVerificationToken(user, Instant.now().plus(24, ChronoUnit.HOURS));

        mockMvc.perform(post("/api/auth/resend-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"resendme"}"""))
                .andExpect(status().isNoContent());

        assertThat(emailVerificationTokenRepository.findByToken(old.getToken())).isEmpty();
        assertThat(emailVerificationTokenRepository.findAll())
                .filteredOn(t -> t.getUser().getId().equals(user.getId()))
                .hasSize(1);
        verify(emailService).sendVerificationEmail(any(User.class), anyString());
    }

    @Test
    void resendVerificationNoopForVerifiedUser() throws Exception {
        createUser("alreadyok");

        mockMvc.perform(post("/api/auth/resend-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"alreadyok"}"""))
                .andExpect(status().isNoContent());

        assertThat(emailVerificationTokenRepository.findAll()).isEmpty();
        verify(emailService, never()).sendVerificationEmail(any(), anyString());
    }

    @Test
    void resendVerificationIsSilentForUnknownUser() throws Exception {
        mockMvc.perform(post("/api/auth/resend-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"nobody@example.com"}"""))
                .andExpect(status().isNoContent());
        verify(emailService, never()).sendVerificationEmail(any(), anyString());
    }
}
