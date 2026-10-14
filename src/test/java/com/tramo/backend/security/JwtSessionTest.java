package com.tramo.backend.security;

import com.tramo.backend.security.jwt.JwtService;
import com.tramo.backend.user.Role;
import com.tramo.backend.user.entity.User;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtSessionTest {
    private static final String SECRET = "dGVzdC1zZWNyZXQtdGVzdC1zZWNyZXQtdGVzdC1zZWNyZXQ=";

    private final JwtService jwtService = new JwtService();
    private final User user = new User();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(jwtService, "secretKey", SECRET);
        ReflectionTestUtils.setField(jwtService, "accessTokenTtlMs", 900000L);
        user.setId(1L);
        user.setUsername("sessionuser");
        user.setRole(Role.USER);
    }

    @Test
    void legacyTokensRemainValidUntilPasswordChanges() {
        String token = Jwts.builder()
                .subject(user.getUsername())
                .claim("id", user.getId())
                .expiration(new Date(System.currentTimeMillis() + 900000))
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET)))
                .compact();

        assertThat(jwtService.isSessionValid(token, user)).isTrue();
        user.setSessionVersion(UUID.randomUUID());
        assertThat(jwtService.isSessionValid(token, user)).isFalse();
    }

    @Test
    void eachRotationRejectsEarlierTokensAndAcceptsNewTokens() {
        String initialToken = jwtService.getToken(user);
        assertThat(jwtService.isSessionValid(initialToken, user)).isTrue();

        user.setSessionVersion(UUID.randomUUID());
        String nextToken = jwtService.getToken(user);
        assertThat(jwtService.isSessionValid(initialToken, user)).isFalse();
        assertThat(jwtService.isSessionValid(nextToken, user)).isTrue();

        user.setSessionVersion(UUID.randomUUID());
        assertThat(jwtService.isSessionValid(initialToken, user)).isFalse();
        assertThat(jwtService.isSessionValid(nextToken, user)).isFalse();
        assertThat(jwtService.isSessionValid(jwtService.getToken(user), user)).isTrue();
    }

    @Test
    void invalidSessionClaimIsRejected() {
        String token = Jwts.builder()
                .claim("sessionVersion", 123)
                .expiration(new Date(System.currentTimeMillis() + 900000))
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET)))
                .compact();

        assertThat(jwtService.isSessionValid(token, user)).isFalse();
        assertThat(jwtService.isSessionValid("not-a-jwt", user)).isFalse();
    }
}
