package com.tramo.backend.security;

import com.tramo.backend.security.jwt.JwtService;
import com.tramo.backend.user.Role;
import com.tramo.backend.user.entity.User;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtSessionTest {
    private static final String SECRET = "dGVzdC1zZWNyZXQtdGVzdC1zZWNyZXQtdGVzdC1zZWNyZXQ=";

    private final JwtService jwtService = new JwtService(SECRET, 900000L);
    private final User user = new User();

    @BeforeEach
    void setUp() {
        user.setId(1L);
        user.setUsername("sessionuser");
        user.setRole(Role.USER);
    }

    @Test
    void tokensWithoutSessionVersionRemainValidUntilPasswordChanges() {
        String token = Jwts.builder().issuer("tramo-api").audience().add("tramo-web").and()
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
        String token = Jwts.builder().issuer("tramo-api").audience().add("tramo-web").and()
                .claim("sessionVersion", 123)
                .expiration(new Date(System.currentTimeMillis() + 900000))
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET)))
                .compact();

        assertThat(jwtService.isSessionValid(token, user)).isFalse();
        assertThat(jwtService.isSessionValid("not-a-jwt", user)).isFalse();
    }
    @Test
    void rejectsMissingOrForeignIssuerAndAudience() {
        for (String issuer : new String[]{null, "other-api", "tramo-api"}) {
            for (String audience : new String[]{null, "other-client", "tramo-web"}) {
                var builder = Jwts.builder().claim("id", user.getId())
                        .expiration(new Date(System.currentTimeMillis() + 900000));
                if (issuer != null) builder.issuer(issuer);
                if (audience != null) builder.audience().add(audience).and();
                String token = builder.signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET))).compact();
                boolean valid = "tramo-api".equals(issuer) && "tramo-web".equals(audience);
                assertThat(jwtService.getUserIdFromToken(token)).isEqualTo(valid ? user.getId() : null);
            }
        }
    }

    @Test
    void rejectsInvalidKeysAndNonPositiveLifetimeAtConstruction() {
        for (String secret : new String[]{"", "not base64!", "c2hvcnQ="}) {
            assertThatThrownBy(() -> new JwtService(secret, 900000L)).isInstanceOf(RuntimeException.class);
        }
        assertThatThrownBy(() -> new JwtService(SECRET, 0)).isInstanceOf(IllegalArgumentException.class);
    }

}
