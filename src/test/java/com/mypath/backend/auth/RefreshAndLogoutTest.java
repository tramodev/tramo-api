package com.mypath.backend.auth;

import com.mypath.backend.AbstractIntegrationTest;
import com.mypath.backend.auth.entity.RefreshToken;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RefreshAndLogoutTest extends AbstractIntegrationTest {

    @Autowired
    RefreshTokenRepository refreshTokenRepository;

    private RefreshToken issueRefreshToken(User user, Instant expiresAt) {
        RefreshToken token = new RefreshToken();
        token.setUser(user);
        token.setToken(UUID.randomUUID().toString());
        token.setExpiresAt(expiresAt);
        return refreshTokenRepository.save(token);
    }

    private ResultActions refresh(String token) throws Exception {
        return mockMvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"refreshToken":"%s"}""".formatted(token)));
    }

    @Test
    void refreshReturnsNewAccessToken() throws Exception {
        User user = createUser("refresher");
        RefreshToken token = issueRefreshToken(user, Instant.now().plus(30, ChronoUnit.DAYS));

        String accessToken = refresh(token.getToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").value(token.getToken()))
                .andExpect(jsonPath("$.username").value("refresher"))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(get("/api/profile/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("refresher"));
    }

    @Test
    void refreshRejectsUnknownToken() throws Exception {
        refresh("no-such-token")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid refresh token"));
    }

    @Test
    void refreshRejectsExpiredToken() throws Exception {
        User user = createUser("expired");
        RefreshToken token = issueRefreshToken(user, Instant.now().minus(1, ChronoUnit.DAYS));

        refresh(token.getToken())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Refresh token expired"));
    }

    @Test
    void logoutDeletesRefreshToken() throws Exception {
        User user = createUser("leaver");
        RefreshToken token = issueRefreshToken(user, Instant.now().plus(30, ChronoUnit.DAYS));

        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}""".formatted(token.getToken())))
                .andExpect(status().isOk());

        assertThat(refreshTokenRepository.findByToken(token.getToken())).isEmpty();
        refresh(token.getToken()).andExpect(status().isUnauthorized());
    }
}
