// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.auth;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.auth.entity.RefreshToken;
import com.tramo.backend.auth.dto.AuthResponse;
import com.tramo.backend.auth.dto.RefreshTokenRequestDTO;
import com.tramo.backend.auth.repository.RefreshTokenRepository;
import com.tramo.backend.auth.service.SessionService;
import com.tramo.backend.auth.service.OpaqueToken;
import com.tramo.backend.exception.InvalidTokenException;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

    private String issueRefreshToken(User user, Instant expiresAt) {
        String rawToken = OpaqueToken.generate();
        RefreshToken token = new RefreshToken();
        token.setUser(user);
        token.setTokenHash(OpaqueToken.hash(rawToken));
        token.setExpiresAt(expiresAt);
        refreshTokenRepository.save(token);
        return rawToken;
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
        String token = issueRefreshToken(user, Instant.now().plus(30, ChronoUnit.DAYS));

        String body = refresh(token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").value(not(token)))
                .andExpect(jsonPath("$.username").value("refresher"))
                .andReturn().getResponse().getContentAsString();
        String accessToken = com.jayway.jsonpath.JsonPath.read(body, "$.accessToken");
        String replacement = com.jayway.jsonpath.JsonPath.read(body, "$.refreshToken");
        assertThat(refreshTokenRepository.findByTokenHash(OpaqueToken.hash(replacement))).isPresent();
        assertThat(refreshTokenRepository.findAll()).extracting(RefreshToken::getTokenHash)
                .containsExactlyInAnyOrder(OpaqueToken.hash(token), OpaqueToken.hash(replacement))
                .doesNotContain(token, replacement);

        mockMvc.perform(get("/api/profile/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("refresher"));
    }

    @Test
    void reusingRotatedTokenDropsTheWholeChain() throws Exception {
        User user = createUser("reuser");
        String token = issueRefreshToken(user, Instant.now().plus(30, ChronoUnit.DAYS));

        String newToken = refresh(token)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"refreshToken\":\"([^\"]+)\".*", "$1");

        refresh(token).andExpect(status().isUnauthorized());

        refresh(newToken).andExpect(status().isUnauthorized());
    }

    @Test
    void concurrentRefreshesRejectReuseAndRevokeAllTokens() throws Exception {
        User user = createUser("concurrent");
        String token = issueRefreshToken(user, Instant.now().plus(30, ChronoUnit.DAYS));
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        java.util.concurrent.Callable<AuthResponse> call = () -> {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
            RefreshTokenRequestDTO request = new RefreshTokenRequestDTO();
            request.setRefreshToken(token);
            try {
                return sessionService.refresh(request);
            } catch (InvalidTokenException ex) {
                return null;
            }
        };
        try {
            var first = executor.submit(call);
            var second = executor.submit(call);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            AuthResponse a = first.get(10, TimeUnit.SECONDS);
            AuthResponse b = second.get(10, TimeUnit.SECONDS);
            assertThat(new AuthResponse[]{a, b}).filteredOn(response -> response != null).hasSize(1);
            assertThat(refreshTokenRepository.findAll()).isEmpty();
            refresh((a != null ? a : b).getRefreshToken()).andExpect(status().isUnauthorized());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void issuedSessionStoresOnlyHashAndRejectsHashAsToken() throws Exception {
        User user = createUser("hashedsession");
        AuthResponse response = sessionService.issueSession(user);
        String token = response.getRefreshToken();
        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        assertThat(refreshTokenRepository.findAll()).extracting(RefreshToken::getTokenHash)
                .containsExactly(OpaqueToken.hash(token)).doesNotContain(token);
        refresh(OpaqueToken.hash(token)).andExpect(status().isUnauthorized());
        refresh(token).andExpect(status().isOk());
    }

    @Test
    void duplicateRefreshCannotRestoreLoggedOutReplacement() throws Exception {
        User user = createUser("duplicateLogout");
        String token = issueRefreshToken(user, Instant.now().plus(30, ChronoUnit.DAYS));
        String response = refresh(token).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String replacement = com.jayway.jsonpath.JsonPath.read(response, "$.refreshToken");
        mockMvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + replacement + "\"}"))
                .andExpect(status().isOk());
        refresh(token).andExpect(status().isUnauthorized());
        assertThat(refreshTokenRepository.findByTokenHash(OpaqueToken.hash(replacement))).isEmpty();
    }

    @Test
    void duplicateRefreshRejectsExpiredReplacement() throws Exception {
        User user = createUser("duplicateExpired");
        String token = issueRefreshToken(user, Instant.now().plus(30, ChronoUnit.DAYS));
        String response = refresh(token).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String replacement = com.jayway.jsonpath.JsonPath.read(response, "$.refreshToken");
        RefreshToken expired = refreshTokenRepository.findByTokenHash(OpaqueToken.hash(replacement)).orElseThrow();
        expired.setExpiresAt(Instant.now().minusSeconds(1));
        refreshTokenRepository.save(expired);
        refresh(token).andExpect(status().isUnauthorized());
    }

    @Test
    void duplicateRefreshRevokesAnAlreadyRotatedReplacement() throws Exception {
        User user = createUser("duplicateAdvanced");
        String token = issueRefreshToken(user, Instant.now().plus(30, ChronoUnit.DAYS));
        String response = refresh(token).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String replacement = com.jayway.jsonpath.JsonPath.read(response, "$.refreshToken");
        String nextResponse = refresh(replacement).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String next = com.jayway.jsonpath.JsonPath.read(nextResponse, "$.refreshToken");
        refresh(token).andExpect(status().isUnauthorized());
        refresh(next).andExpect(status().isUnauthorized());
    }

    @Test
    void refreshRejectsUserBannedAfterTokenIssuance() throws Exception {
        User user = createUser("bannedrefresh");
        String token = issueRefreshToken(user, Instant.now().plus(30, ChronoUnit.DAYS));
        user.setBanned(true);
        userRepository.save(user);
        refresh(token).andExpect(status().isUnauthorized());
    }

    @Test
    void duplicateRefreshChecksCurrentBan() throws Exception {
        User user = createUser("bannedretry");
        String token = issueRefreshToken(user, Instant.now().plus(30, ChronoUnit.DAYS));
        refresh(token).andExpect(status().isOk());
        user.setBanned(true);
        userRepository.save(user);
        refresh(token).andExpect(status().isUnauthorized());
    }

    @Test
    void refreshUsesCurrentRole() throws Exception {
        User user = createAdmin("demotedrefresh");
        String token = issueRefreshToken(user, Instant.now().plus(30, ChronoUnit.DAYS));
        user.setRole(com.tramo.backend.user.Role.USER);
        userRepository.save(user);
        String response = refresh(token).andExpect(status().isOk())
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

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"refreshToken\":null}", "{\"refreshToken\":\"\"}", "{\"refreshToken\":\" \"}"})
    void refreshAndLogoutRejectMissingOrBlankTokens(String body) throws Exception {
        for (String endpoint : java.util.List.of("/api/auth/refresh", "/api/auth/logout")) {
            mockMvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.refreshToken").value("Token is required"));
        }
    }

    @Test
    void refreshRejectsExpiredToken() throws Exception {
        User user = createUser("expired");
        String token = issueRefreshToken(user, Instant.now().minus(1, ChronoUnit.DAYS));

        refresh(token)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Refresh token expired"));
    }

    @Test
    void logoutDeletesRefreshToken() throws Exception {
        User user = createUser("leaver");
        String token = issueRefreshToken(user, Instant.now().plus(30, ChronoUnit.DAYS));

        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}""".formatted(token)))
                .andExpect(status().isOk());

        assertThat(refreshTokenRepository.findByTokenHash(OpaqueToken.hash(token))).isEmpty();
        refresh(token).andExpect(status().isUnauthorized());
    }

    @Test
    void purgeDeletesExpiredAndStaleRevokedTokens() {
        User user = createUser("purgeable");
        RefreshToken expiredRevoked = refreshTokenRepository.findByTokenHash(OpaqueToken.hash(
                issueRefreshToken(user, Instant.now().minus(1, ChronoUnit.DAYS)))).orElseThrow();
        expiredRevoked.setRevoked(true);
        refreshTokenRepository.save(expiredRevoked);
        String expiredActive = issueRefreshToken(user, Instant.now().minus(1, ChronoUnit.DAYS));
        RefreshToken revokedPastGracePeriod = refreshTokenRepository.findByTokenHash(OpaqueToken.hash(
                issueRefreshToken(user, Instant.now().plus(30, ChronoUnit.DAYS)))).orElseThrow();
        revokedPastGracePeriod.setRevoked(true);
        revokedPastGracePeriod.setRevokedAt(Instant.now().minus(3, ChronoUnit.DAYS));
        refreshTokenRepository.save(revokedPastGracePeriod);
        RefreshToken revokedRecently = refreshTokenRepository.findByTokenHash(OpaqueToken.hash(
                issueRefreshToken(user, Instant.now().plus(30, ChronoUnit.DAYS)))).orElseThrow();
        revokedRecently.setRevoked(true);
        revokedRecently.setRevokedAt(Instant.now().minus(1, ChronoUnit.HOURS));
        refreshTokenRepository.save(revokedRecently);
        String liveActive = issueRefreshToken(user, Instant.now().plus(30, ChronoUnit.DAYS));

        sessionService.purgeExpiredRefreshTokens();

        assertThat(refreshTokenRepository.findByTokenHash(expiredRevoked.getTokenHash())).isEmpty();
        assertThat(refreshTokenRepository.findByTokenHash(OpaqueToken.hash(expiredActive))).isEmpty();
        assertThat(refreshTokenRepository.findByTokenHash(revokedPastGracePeriod.getTokenHash())).isEmpty();
        assertThat(refreshTokenRepository.findByTokenHash(revokedRecently.getTokenHash())).isPresent();
        assertThat(refreshTokenRepository.findByTokenHash(OpaqueToken.hash(liveActive))).isPresent();
    }
}
