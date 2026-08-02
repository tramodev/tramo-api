package com.tramo.backend.security;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.user.Role;
import com.tramo.backend.user.entity.User;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class JwtFilterTest extends AbstractIntegrationTest {

    private String signedToken(String username, String base64Secret, long expiresInMs) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .setSubject(username)
                .setIssuedAt(new Date(now - 10_000))
                .setExpiration(new Date(now + expiresInMs))
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(base64Secret)), SignatureAlgorithm.HS256)
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
    void malformedTokenIsRejectedWith401() throws Exception {
        mockMvc.perform(get("/api/profile/me").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredTokenIsRejectedWith401() throws Exception {
        User user = createUser("sleepy");
        String expired = signedToken(user.getUsername(), TEST_JWT_SECRET, -60_000);

        mockMvc.perform(get("/api/profile/me").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenSignedWithWrongKeyIsRejectedWith401() throws Exception {
        User user = createUser("victim");
        String forged = signedToken(user.getUsername(),
                "d3Jvbmctc2VjcmV0LXdyb25nLXNlY3JldC13cm9uZy1zZWNyZXQ=", 60_000);

        mockMvc.perform(get("/api/profile/me").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenForDeletedUserStaysValidUntilExpiry() throws Exception {
        // The filter no longer hits the DB per request (id/role/emailVerified/banned
        // are embedded as JWT claims at issuance) - an already-issued token keeps
        // authenticating even after the user row is gone, until it naturally expires.
        // /api/profile/me itself still re-fetches fresh (see ProjectService.getProfile),
        // so it 404s rather than returning stale data.
        User user = createUser("goner");
        String token = bearer(user);
        userRepository.delete(user);

        mockMvc.perform(get("/api/profile/me").header("Authorization", token))
                .andExpect(status().isNotFound());
    }

    @Test
    void banDoesNotInvalidateAlreadyIssuedTokenButBlocksNewOnes() throws Exception {
        // Accepted tradeoff of embedding banned/role/emailVerified as JWT claims instead
        // of re-checking the DB every request: an existing token keeps authenticating
        // until it expires or the user hits /api/auth/refresh (which does re-check
        // banned against a fresh row and rejects - see AuthService.refresh). A freshly
        // issued token for an already-banned user is rejected immediately.
        User user = createUser("banme");
        String token = bearer(user);

        mockMvc.perform(get("/api/profile/me").header("Authorization", token))
                .andExpect(status().isOk());

        user.setBanned(true);
        userRepository.save(user);

        mockMvc.perform(get("/api/profile/me").header("Authorization", token))
                .andExpect(status().isOk());

        String freshToken = bearer(user);
        mockMvc.perform(get("/api/profile/me").header("Authorization", freshToken))
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
        String expired = signedToken(user.getUsername(), TEST_JWT_SECRET, -60_000);

        mockMvc.perform(get("/api/public/explore").header("Authorization", "Bearer " + expired))
                .andExpect(status().isOk());
    }

    @Test
    void anonymousCanReadPublicEndpoints() throws Exception {
        mockMvc.perform(get("/api/public/explore"))
                .andExpect(status().isOk());
    }
}
