// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.auth.service;

import com.tramo.backend.auth.dto.AuthResponse;
import com.tramo.backend.auth.dto.LoginRequestDTO;
import com.tramo.backend.auth.dto.RefreshTokenRequestDTO;
import com.tramo.backend.auth.entity.RefreshToken;
import com.tramo.backend.auth.repository.RefreshTokenRepository;
import com.tramo.backend.exception.InvalidTokenException;
import com.tramo.backend.security.jwt.JwtService;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.user.repository.UserRepository;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.time.Instant;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

@Service
public class SessionService {
    private static final Duration REFRESH_RETRY_WINDOW = Duration.ofSeconds(5);
    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final RefreshTokenRepository refreshTokenRepository;
    private final IdentityRateLimiter identityRateLimiter;
    private final byte[] refreshDerivationKey;

    public SessionService(UserRepository userRepository, JwtService jwtService,
                          AuthenticationManager authenticationManager,
                          RefreshTokenRepository refreshTokenRepository,
                          IdentityRateLimiter identityRateLimiter,
                          @Value("${app.jwt.secret}") String secret) {
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.authenticationManager = authenticationManager;
        this.refreshTokenRepository = refreshTokenRepository;
        this.identityRateLimiter = identityRateLimiter;
        this.refreshDerivationKey = Base64.getDecoder().decode(secret);
    }

    public AuthResponse issueSession(User user) {
        String refreshToken = OpaqueToken.generate();
        createRefreshToken(user, refreshToken);
        return issueSession(user, refreshToken);
    }

    private AuthResponse issueSession(User user, String refreshToken) {
        return new AuthResponse(jwtService.getToken(user), refreshToken, user.getUsername(), user.getBirthDate() == null);
    }

    private void createRefreshToken(User user, String token) {
        RefreshToken rt = new RefreshToken();
        rt.setUser(user);
        rt.setTokenHash(OpaqueToken.hash(token));
        rt.setExpiresAt(Instant.now().plus(30, ChronoUnit.DAYS));
        refreshTokenRepository.save(rt);
    }

    public AuthResponse login(LoginRequestDTO request) {
        if (request.getUsername() != null && !request.getUsername().isBlank()) {
            identityRateLimiter.check("login", request.getUsername(), 10, 10);
        }

        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.getUsername(),
                        request.getPassword()
                )
        );

        User user = userRepository.findByUsernameIgnoreCase(request.getUsername())
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));

        return issueSession(user);
    }

    @Transactional(dontRollbackOn = InvalidTokenException.class)
    public AuthResponse refresh(RefreshTokenRequestDTO request) {
        RefreshToken refreshToken = refreshTokenRepository
                .findByTokenHashForUpdate(OpaqueToken.hash(request.getRefreshToken()))
                .orElseThrow(() -> new InvalidTokenException("Invalid refresh token"));

        Instant now = Instant.now();
        if (!refreshToken.getExpiresAt().isAfter(now)) {
            throw new InvalidTokenException("Refresh token expired");
        }

        User user = refreshToken.getUser();
        if (user.isBanned()) {
            throw new InvalidTokenException("Invalid refresh token");
        }

        if (refreshToken.isRevoked()) {
            if (refreshToken.getRevokedAt() != null
                    && now.isBefore(refreshToken.getRevokedAt().plus(REFRESH_RETRY_WINDOW))) {
                String replacement = OpaqueToken.replacement(request.getRefreshToken(), refreshDerivationKey);
                refreshTokenRepository.findByTokenHashForUpdate(OpaqueToken.hash(replacement))
                        .filter(token -> !token.isRevoked() && token.getExpiresAt().isAfter(Instant.now()))
                        .filter(token -> token.getUser().getId().equals(user.getId()))
                        .orElseThrow(() -> new InvalidTokenException("Invalid refresh token"));
                return issueSession(user, replacement);
            }
            refreshTokenRepository.deleteByUserId(user.getId());
            throw new InvalidTokenException("Invalid refresh token");
        }

        String replacement = OpaqueToken.replacement(request.getRefreshToken(), refreshDerivationKey);
        createRefreshToken(user, replacement);
        AuthResponse response = issueSession(user, replacement);
        refreshToken.setRevoked(true);
        refreshToken.setRevokedAt(now);
        refreshTokenRepository.save(refreshToken);
        return response;
    }

    @Transactional
    public void logout(RefreshTokenRequestDTO request) {
        refreshTokenRepository.deleteByTokenHash(OpaqueToken.hash(request.getRefreshToken()));
    }

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void purgeExpiredRefreshTokens() {
        long deletedRefreshTokens = refreshTokenRepository.deleteByExpiresAtBefore(Instant.now());
        if (deletedRefreshTokens > 0) {
            log.info("purgeExpiredRefreshTokens deleted {} expired refresh tokens", deletedRefreshTokens);
        }

        long deletedRevokedRefreshTokens = refreshTokenRepository
                .deleteByRevokedTrueAndRevokedAtBefore(Instant.now().minus(48, ChronoUnit.HOURS));
        if (deletedRevokedRefreshTokens > 0) {
            log.info("purgeExpiredRefreshTokens deleted {} revoked refresh tokens past the grace period", deletedRevokedRefreshTokens);
        }
    }
}
