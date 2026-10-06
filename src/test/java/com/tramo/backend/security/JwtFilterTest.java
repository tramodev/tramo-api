// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.security;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.user.Role;
import com.tramo.backend.user.entity.User;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class JwtFilterTest extends AbstractIntegrationTest {

    private String signedToken(User user, String base64Secret, long expiresInMs) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(user.getUsername())
                .claim("id", user.getId())
                .claim("role", user.getRole().name())
                .claim("emailVerified", true)
                .issuedAt(new Date(now - 10_000))
                .expiration(new Date(now + expiresInMs))
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(base64Secret)))
                .compact();
    }

    @Test
    void protectedEndpointRequiresToken() throws Exception {
        mockMvc.perform(get("/api/profile/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validTokenAuthenticates() throws Exception {
        User user = createUser("tokenuser");
        mockMvc.perform(get("/api/profile/me").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("tokenuser"));
    }

    @Test
    void authenticationAddsOnlyOneUserQueryPerRequest() throws Exception {
        User user = createUser("singlelookup");
        String token = bearer(user);
        assertThat(queryCount(() -> mockMvc.perform(get("/api/notifications/unread-count")
                .header("Authorization", token)).andExpect(status().isOk()))).isEqualTo(2);
        assertThat(queryCount(() -> mockMvc.perform(get("/api/notifications/unread-count")
                .header("Authorization", "Bearer not-a-jwt")).andExpect(status().isUnauthorized()))).isZero();
    }

    @Test
    void disablingUserRejectsEarlierVerifiedToken() throws Exception {
        User user = createUser("disabled");
        String token = bearer(user);
        user.setEmailVerified(false);
        userRepository.save(user);
        mockMvc.perform(get("/api/profile/me").header("Authorization", token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void malformedTokenIsRejectedWith401() throws Exception {
        mockMvc.perform(get("/api/profile/me").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredTokenIsRejectedWith401() throws Exception {
        User user = createUser("sleepy");
        String expired = signedToken(user, TEST_JWT_SECRET, -60_000);

        mockMvc.perform(get("/api/profile/me").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void signedTokenWithoutExpirationIsRejected() throws Exception {
        User user = createUser("noexpiry");
        String token = Jwts.builder().subject(user.getUsername()).claim("id", user.getId())
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(TEST_JWT_SECRET))).compact();
        mockMvc.perform(get("/api/profile/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenSignedWithWrongKeyIsRejectedWith401() throws Exception {
        User user = createUser("victim");
        String forged = signedToken(user,
                "d3Jvbmctc2VjcmV0LXdyb25nLXNlY3JldC13cm9uZy1zZWNyZXQ=", 60_000);

        mockMvc.perform(get("/api/profile/me").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenForDeletedUserIsRejectedImmediately() throws Exception {
        User user = createUser("goner");
        String token = bearer(user);
        userRepository.delete(user);

        mockMvc.perform(get("/api/profile/me").header("Authorization", token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void banRejectsAlreadyIssuedTokenImmediately() throws Exception {
        User user = createUser("banme");
        String token = bearer(user);

        mockMvc.perform(get("/api/profile/me").header("Authorization", token))
                .andExpect(status().isOk());

        user.setBanned(true);
        userRepository.save(user);

        mockMvc.perform(get("/api/profile/me").header("Authorization", token))
                .andExpect(status().isUnauthorized());

        String freshToken = bearer(user);
        mockMvc.perform(get("/api/profile/me").header("Authorization", freshToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void currentRoleControlsRealAdminEndpointWithSameToken() throws Exception {
        User user = createAdmin("changingadmin");
        String token = bearer(user);
        mockMvc.perform(get("/api/admin/reports").header("Authorization", token))
                .andExpect(status().isOk());

        user.setRole(Role.USER);
        userRepository.save(user);
        mockMvc.perform(get("/api/admin/reports").header("Authorization", token))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/profile/me").header("Authorization", token))
                .andExpect(status().isOk());

        user.setRole(Role.ADMIN);
        userRepository.save(user);
        mockMvc.perform(get("/api/admin/reports").header("Authorization", token))
                .andExpect(status().isOk());
    }

    @Test
    void promotionAppliesWithoutIssuingAnotherAccessToken() throws Exception {
        User user = createUser("promoted");
        String token = bearer(user);
        mockMvc.perform(get("/api/admin/reports").header("Authorization", token))
                .andExpect(status().isForbidden());
        user.setRole(Role.ADMIN);
        userRepository.save(user);
        mockMvc.perform(get("/api/admin/reports").header("Authorization", token))
                .andExpect(status().isOk());
    }

    @Test
    void revokedTokenCannotBecomeAnonymousOnPublicEndpoints() throws Exception {
        User user = createUser("publicban");
        String token = bearer(user);
        user.setBanned(true);
        userRepository.save(user);
        mockMvc.perform(get("/api/public/explore").header("Authorization", token))
                .andExpect(status().isUnauthorized());
        userRepository.delete(user);
        mockMvc.perform(get("/api/public/explore").header("Authorization", token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unbanRestoresUnexpiredAccessToken() throws Exception {
        User user = createUser("unbanned");
        String token = bearer(user);
        user.setBanned(true);
        userRepository.save(user);
        mockMvc.perform(get("/api/profile/me").header("Authorization", token))
                .andExpect(status().isUnauthorized());
        user.setBanned(false);
        userRepository.save(user);
        mockMvc.perform(get("/api/profile/me").header("Authorization", token))
                .andExpect(status().isOk());
    }

    @Test
    void tokenIdentityUsesIdInsteadOfReusedUsername() throws Exception {
        User user = createAdmin("reused");
        String token = bearer(user);
        userRepository.delete(user);
        createAdmin("reused");
        mockMvc.perform(get("/api/admin/reports").header("Authorization", token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unverifiedUserTokenIsRejected() throws Exception {
        User user = createUser("shadow", "shadow@example.com", false, false, Role.USER);
        mockMvc.perform(get("/api/profile/me").header("Authorization", bearer(user)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void staleTokenDoesNotBreakPublicEndpoints() throws Exception {
        User user = createUser("wanderer");
        String expired = signedToken(user, TEST_JWT_SECRET, -60_000);

        mockMvc.perform(get("/api/public/explore").header("Authorization", "Bearer " + expired))
                .andExpect(status().isOk());
    }

    @Test
    void anonymousCanReadPublicEndpoints() throws Exception {
        mockMvc.perform(get("/api/public/explore"))
                .andExpect(status().isOk());
    }
}
