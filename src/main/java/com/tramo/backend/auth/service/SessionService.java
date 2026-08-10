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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class SessionService {
    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final RefreshTokenRepository refreshTokenRepository;
    private final IdentityRateLimiter identityRateLimiter;

    public SessionService(UserRepository userRepository, JwtService jwtService,
                          AuthenticationManager authenticationManager,
                          RefreshTokenRepository refreshTokenRepository,
                          IdentityRateLimiter identityRateLimiter) {
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.authenticationManager = authenticationManager;
        this.refreshTokenRepository = refreshTokenRepository;
        this.identityRateLimiter = identityRateLimiter;
    }

    public AuthResponse issueSession(User user) {
        String accessToken = jwtService.getToken(user);
        RefreshToken refreshToken = createRefreshToken(user);
        return new AuthResponse(accessToken, refreshToken.getToken(), user.getUsername(), user.getBirthDate() == null);
    }

    public RefreshToken createRefreshToken(User user) {
        RefreshToken rt = new RefreshToken();
        rt.setUser(user);
        rt.setToken(UUID.randomUUID().toString());
        rt.setExpiresAt(Instant.now().plus(30, ChronoUnit.DAYS));
        return refreshTokenRepository.save(rt);
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
                .findByToken(request.getRefreshToken())
                .orElseThrow(() -> new InvalidTokenException("Invalid refresh token"));

        if (refreshToken.isRevoked()) {
            refreshTokenRepository.deleteByUserId(refreshToken.getUser().getId());
            throw new InvalidTokenException("Invalid refresh token");
        }

        if (refreshToken.getExpiresAt().isBefore(Instant.now())) {
            throw new InvalidTokenException("Refresh token expired");
        }

        User user = refreshToken.getUser();
        if (user.isBanned()) {
            throw new InvalidTokenException("Invalid refresh token");
        }

        refreshToken.setRevoked(true);
        refreshToken.setRevokedAt(Instant.now());
        refreshTokenRepository.save(refreshToken);

        return issueSession(user);
    }

    @Transactional
    public void logout(RefreshTokenRequestDTO request) {
        refreshTokenRepository.deleteByToken(request.getRefreshToken());
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
