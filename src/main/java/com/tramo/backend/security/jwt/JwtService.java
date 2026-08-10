package com.tramo.backend.security.jwt;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import com.tramo.backend.user.Role;
import com.tramo.backend.user.entity.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;

@Service
public class JwtService {

    @Value("${app.jwt.secret}")
    private String secretKey;

    @Value("${app.jwt.access-token-ttl-ms:900000}")
    private long accessTokenTtlMs;

    public String getToken(User user) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("id", user.getId());
        claims.put("role", user.getRole().name());
        claims.put("emailVerified", user.isEmailVerified());
        claims.put("banned", user.isBanned());
        claims.put("requiresBirthDate", user.getBirthDate() == null);
        return getToken(claims, user.getUsername());
    }

    private String getToken(Map<String,Object> extraClaims, String username) {
        return Jwts
                .builder()
                .claims(extraClaims)
                .subject(username)
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + accessTokenTtlMs))
                .signWith(getKey())
                .compact();
    }

    private SecretKey getKey() {
        byte[] keyBytes= Decoders.BASE64.decode(secretKey);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    public String getUsernameFromToken(String token) {
        return getClaim(token, Claims::getSubject);
    }

    public User buildPrincipalFromClaims(String token) {
        try {
            Claims claims = getAllClaims(token);
            User user = new User();
            user.setId(((Number) claims.get("id")).longValue());
            user.setUsername(claims.getSubject());
            user.setRole(Role.valueOf((String) claims.get("role")));
            user.setEmailVerified(Boolean.TRUE.equals(claims.get("emailVerified", Boolean.class)));
            user.setBanned(Boolean.TRUE.equals(claims.get("banned", Boolean.class)));
            user.setRequiresBirthDate(Boolean.TRUE.equals(claims.get("requiresBirthDate", Boolean.class)));
            return user;
        } catch (JwtException | IllegalArgumentException | NullPointerException | ClassCastException e) {
            return null;
        }
    }

    public boolean isTokenValid(String token) {
        return !isTokenExpired(token);
    }

    private Claims getAllClaims(String token)
    {
        return Jwts
                .parser()
                .verifyWith(getKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public <T> T getClaim(String token, Function<Claims,T> claimsResolver)
    {
        final Claims claims=getAllClaims(token);
        return claimsResolver.apply(claims);
    }

    private Date getExpiration(String token)
    {
        return getClaim(token, Claims::getExpiration);
    }

    private boolean isTokenExpired(String token)
    {
        return getExpiration(token).before(new Date());
    }

}
