package com.tramo.backend.auth.service;

import com.tramo.backend.auth.entity.PasswordResetToken;
import com.tramo.backend.auth.repository.PasswordResetTokenRepository;
import com.tramo.backend.auth.repository.RefreshTokenRepository;
import com.tramo.backend.exception.InvalidTokenException;
import com.tramo.backend.exception.ResourceNotFoundException;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.user.repository.UserRepository;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class PasswordService {
    private static final Logger log = LoggerFactory.getLogger(PasswordService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final EmailService emailService;
    private final IdentityRateLimiter identityRateLimiter;

    public PasswordService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                           PasswordResetTokenRepository passwordResetTokenRepository,
                           RefreshTokenRepository refreshTokenRepository, EmailService emailService,
                           IdentityRateLimiter identityRateLimiter) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.emailService = emailService;
        this.identityRateLimiter = identityRateLimiter;
    }

    @Transactional
    public void forgotPassword(String email) {
        if (email != null && !email.isBlank()) {
            identityRateLimiter.check("forgot-password", email, 3, 3);
        }

        userRepository.findByEmail(email).ifPresent(user -> {
            passwordResetTokenRepository.deleteByUserId(user.getId());
            PasswordResetToken token = createPasswordResetToken(user);
            emailService.sendPasswordResetEmail(user, token.getToken());
        });
    }

    @Transactional(dontRollbackOn = InvalidTokenException.class)
    public void resetPassword(String token, String newPassword) {
        PasswordResetToken resetToken = passwordResetTokenRepository.findByToken(token)
                .orElseThrow(() -> new InvalidTokenException("Invalid or expired reset link"));

        if (resetToken.getExpiresAt().isBefore(Instant.now())) {
            passwordResetTokenRepository.delete(resetToken);
            throw new InvalidTokenException("Invalid or expired reset link");
        }

        User user = resetToken.getUser();
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        passwordResetTokenRepository.delete(resetToken);

        refreshTokenRepository.deleteByUserId(user.getId());
    }

    @Transactional
    public void changePassword(User principal, String currentPassword, String newPassword) {
        User user = userRepository.findById(principal.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (user.getPassword() == null || !passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw new IllegalArgumentException("Current password is incorrect");
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        refreshTokenRepository.deleteByUserId(user.getId());
    }

    private PasswordResetToken createPasswordResetToken(User user) {
        PasswordResetToken token = new PasswordResetToken();
        token.setUser(user);
        token.setToken(UUID.randomUUID().toString());
        token.setExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS));
        return passwordResetTokenRepository.save(token);
    }

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void purgeExpiredResetTokens() {
        long deletedResetTokens = passwordResetTokenRepository.deleteByExpiresAtBefore(Instant.now());
        if (deletedResetTokens > 0) {
            log.info("purgeExpiredResetTokens deleted {} expired password reset tokens", deletedResetTokens);
        }
    }
}
