package com.tramo.backend.auth;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.auth.entity.RefreshToken;
import com.tramo.backend.auth.dto.AuthResponse;
import com.tramo.backend.auth.dto.RefreshTokenRequestDTO;
import com.tramo.backend.auth.repository.RefreshTokenRepository;
import com.tramo.backend.auth.service.SessionService;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RefreshAndLogoutTest extends AbstractIntegrationTest {

    @Autowired
    RefreshTokenRepository refreshTokenRepository;

    @Autowired
    SessionService sessionService;

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
    void refreshRotatesTokenAndReturnsWorkingAccessToken() throws Exception {
        User user = createUser("refresher");
        RefreshToken token = issueRefreshToken(user, Instant.now().plus(30, ChronoUnit.DAYS));

        String accessToken = refresh(token.getToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").value(not(token.getToken())))
                .andExpect(jsonPath("$.username").value("refresher"))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(get("/api/profile/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("refresher"));
    }

    @Test
    void reusingRotatedTokenDropsTheWholeChain() throws Exception {
        User user = createUser("reuser");
        RefreshToken token = issueRefreshToken(user, Instant.now().plus(30, ChronoUnit.DAYS));

        String newToken = refresh(token.getToken())
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"refreshToken\":\"([^\"]+)\".*", "$1");

        
        RefreshToken rotated = refreshTokenRepository.findByToken(token.getToken()).orElseThrow();
        rotated.setRevokedAt(Instant.now().minusSeconds(6));
        refreshTokenRepository.save(rotated);
        refresh(token.getToken()).andExpect(status().isUnauthorized());

        refresh(newToken).andExpect(status().isUnauthorized());
    }

    @Test
    void concurrentRefreshesReturnOneReplacement() throws Exception {
        User user = createUser("concurrent");
        RefreshToken token = issueRefreshToken(user, Instant.now().plus(30, ChronoUnit.DAYS));
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        java.util.concurrent.Callable<AuthResponse> call = () -> {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
            RefreshTokenRequestDTO request = new RefreshTokenRequestDTO();
            request.setRefreshToken(token.getToken());
            return sessionService.refresh(request);
        };
        try {
            var first = executor.submit(call);
            var second = executor.submit(call);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            AuthResponse a = first.get(10, TimeUnit.SECONDS);
            AuthResponse b = second.get(10, TimeUnit.SECONDS);
            assertThat(a.getRefreshToken()).isEqualTo(b.getRefreshToken()).isNotEqualTo(token.getToken());
            assertThat(refreshTokenRepository.findAll()).hasSize(2);
            refresh(a.getRefreshToken()).andExpect(status().isOk());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void duplicateRefreshKeepsOriginalWindow() throws Exception {
        User user = createUser("duplicate");
        RefreshToken token = issueRefreshToken(user, Instant.now().plus(30, ChronoUnit.DAYS));
        String response = refresh(token.getToken()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String replacement = com.jayway.jsonpath.JsonPath.read(response, "$.refreshToken");
        Instant revokedAt = refreshTokenRepository.findByToken(token.getToken()).orElseThrow().getRevokedAt();
        refresh(token.getToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").value(replacement));
        assertThat(refreshTokenRepository.findByToken(token.getToken()).orElseThrow().getRevokedAt())
                .isEqualTo(revokedAt);
        assertThat(refreshTokenRepository.findAll()).hasSize(2);
    }

    @Test
    void duplicateRefreshCannotRestoreLoggedOutReplacement() throws Exception {
        User user = createUser("duplicateLogout");
        RefreshToken token = issueRefreshToken(user, Instant.now().plus(30, ChronoUnit.DAYS));
        String response = refresh(token.getToken()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String replacement = com.jayway.jsonpath.JsonPath.read(response, "$.refreshToken");
        mockMvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + replacement + "\"}"))
                .andExpect(status().isOk());
        refresh(token.getToken()).andExpect(status().isUnauthorized());
        assertThat(refreshTokenRepository.findByToken(replacement)).isEmpty();
    }

    @Test
    void duplicateRefreshRejectsExpiredReplacement() throws Exception {
        User user = createUser("duplicateExpired");
        RefreshToken token = issueRefreshToken(user, Instant.now().plus(30, ChronoUnit.DAYS));
        String response = refresh(token.getToken()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String replacement = com.jayway.jsonpath.JsonPath.read(response, "$.refreshToken");
        RefreshToken expired = refreshTokenRepository.findByToken(replacement).orElseThrow();
        expired.setExpiresAt(Instant.now().minusSeconds(1));
        refreshTokenRepository.save(expired);
        refresh(token.getToken()).andExpect(status().isUnauthorized());
    }

    @Test
    void duplicateRefreshDoesNotRevokeAnAlreadyRotatedReplacement() throws Exception {
        User user = createUser("duplicateAdvanced");
        RefreshToken token = issueRefreshToken(user, Instant.now().plus(30, ChronoUnit.DAYS));
        String response = refresh(token.getToken()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String replacement = com.jayway.jsonpath.JsonPath.read(response, "$.refreshToken");
        String nextResponse = refresh(replacement).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String next = com.jayway.jsonpath.JsonPath.read(nextResponse, "$.refreshToken");
        refresh(token.getToken()).andExpect(status().isUnauthorized());
        refresh(next).andExpect(status().isOk());
    }

    @Test
    void refreshRejectsUserBannedAfterTokenIssuance() throws Exception {
        User user = createUser("bannedrefresh");
        RefreshToken token = issueRefreshToken(user, Instant.now().plus(30, ChronoUnit.DAYS));
        user.setBanned(true);
        userRepository.save(user);
        refresh(token.getToken()).andExpect(status().isUnauthorized());
    }

    @Test
    void duplicateRefreshChecksCurrentBanBeforeRetryWindow() throws Exception {
        User user = createUser("bannedretry");
        RefreshToken token = issueRefreshToken(user, Instant.now().plus(30, ChronoUnit.DAYS));
        refresh(token.getToken()).andExpect(status().isOk());
        user.setBanned(true);
        userRepository.save(user);
        refresh(token.getToken()).andExpect(status().isUnauthorized());
    }

    @Test
    void refreshUsesCurrentRole() throws Exception {
        User user = createAdmin("demotedrefresh");
        RefreshToken token = issueRefreshToken(user, Instant.now().plus(30, ChronoUnit.DAYS));
        user.setRole(com.tramo.backend.user.Role.USER);
        userRepository.save(user);
        String response = refresh(token.getToken()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String accessToken = com.jayway.jsonpath.JsonPath.read(response, "$.accessToken");
        String role = jwtService.getClaim(accessToken, claims -> claims.get("role", String.class));
        assertThat(role).isEqualTo("USER");
        mockMvc.perform(get("/api/admin/reports").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isForbidden());
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

    @Test
    void purgeDeletesExpiredAndStaleRevokedTokens() {
        User user = createUser("purgeable");
        RefreshToken expiredRevoked = issueRefreshToken(user, Instant.now().minus(1, ChronoUnit.DAYS));
        expiredRevoked.setRevoked(true);
        refreshTokenRepository.save(expiredRevoked);
        RefreshToken expiredActive = issueRefreshToken(user, Instant.now().minus(1, ChronoUnit.DAYS));
        RefreshToken revokedPastGracePeriod = issueRefreshToken(user, Instant.now().plus(30, ChronoUnit.DAYS));
        revokedPastGracePeriod.setRevoked(true);
        revokedPastGracePeriod.setRevokedAt(Instant.now().minus(3, ChronoUnit.DAYS));
        refreshTokenRepository.save(revokedPastGracePeriod);
        RefreshToken revokedRecently = issueRefreshToken(user, Instant.now().plus(30, ChronoUnit.DAYS));
        revokedRecently.setRevoked(true);
        revokedRecently.setRevokedAt(Instant.now().minus(1, ChronoUnit.HOURS));
        refreshTokenRepository.save(revokedRecently);
        RefreshToken liveActive = issueRefreshToken(user, Instant.now().plus(30, ChronoUnit.DAYS));

        sessionService.purgeExpiredRefreshTokens();

        assertThat(refreshTokenRepository.findByToken(expiredRevoked.getToken())).isEmpty();
        assertThat(refreshTokenRepository.findByToken(expiredActive.getToken())).isEmpty();
        assertThat(refreshTokenRepository.findByToken(revokedPastGracePeriod.getToken())).isEmpty();
        assertThat(refreshTokenRepository.findByToken(revokedRecently.getToken())).isPresent();
        assertThat(refreshTokenRepository.findByToken(liveActive.getToken())).isPresent();
    }
}
