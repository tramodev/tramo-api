// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.security.jwt;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import com.tramo.backend.user.entity.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;

@Service
public class JwtService {

    private final SecretKey key;
    private final JwtParser parser;
    private final long accessTokenTtlMs;

    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.access-token-ttl-ms:900000}") long accessTokenTtlMs) {
        this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
        if (accessTokenTtlMs <= 0) throw new IllegalArgumentException("Access token TTL must be positive");
        this.accessTokenTtlMs = accessTokenTtlMs;
        this.parser = Jwts.parser().verifyWith(key)
                .requireIssuer("tramo-api").requireAudience("tramo-web").build();
    }

    public String getToken(User user) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("id", user.getId());
        claims.put("role", user.getRole().name());
        claims.put("emailVerified", user.isEmailVerified());
        claims.put("banned", user.isBanned());
        claims.put("requiresBirthDate", user.getBirthDate() == null);
        claims.put("sessionVersion", Objects.toString(user.getSessionVersion(), null));
        return getToken(claims, user.getUsername());
    }

    private String getToken(Map<String,Object> extraClaims, String username) {
        return Jwts
                .builder()
                .claims(extraClaims)
                .issuer("tramo-api")
                .audience().add("tramo-web").and()
                .subject(username)
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + accessTokenTtlMs))
                .signWith(key)
                .compact();
    }

    public Long getUserIdFromToken(String token) {
        try {
            Claims claims = getAllClaims(token);
            if (claims.getExpiration() == null) return null;
            Number id = claims.get("id", Number.class);
            return id == null ? null : id.longValue();
        } catch (JwtException | IllegalArgumentException | ClassCastException e) {
            return null;
        }
    }

    public boolean isSessionValid(String token, User user) {
        try {
            String version = getClaim(token, claims -> claims.get("sessionVersion", String.class));
            return Objects.equals(version, Objects.toString(user.getSessionVersion(), null));
        } catch (JwtException | IllegalArgumentException | ClassCastException e) {
            return false;
        }
    }

    private Claims getAllClaims(String token)
    {
        return parser.parseSignedClaims(token)
                .getPayload();
    }

    public <T> T getClaim(String token, Function<Claims,T> claimsResolver)
    {
        final Claims claims=getAllClaims(token);
        return claimsResolver.apply(claims);
    }

}
