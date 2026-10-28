// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.auth.service;

import com.tramo.backend.common.SafeLog;
import com.tramo.backend.exception.RequestErrorCode;
import com.tramo.backend.exception.RequestValidationException;
import com.tramo.backend.auth.entity.PasswordResetToken;
import com.tramo.backend.auth.repository.PasswordResetTokenRepository;
import com.tramo.backend.auth.repository.RefreshTokenRepository;
import com.tramo.backend.exception.InvalidTokenException;
import com.tramo.backend.exception.ResourceNotFoundException;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.user.repository.UserRepository;
import com.tramo.backend.notification.service.NotificationService;
import jakarta.transaction.Transactional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final NotificationService notifications;
    private final TransactionTemplate transactionTemplate;
    private final AsyncTaskExecutor recoveryExecutor;
    @PersistenceContext
    private EntityManager entityManager;

    public PasswordService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                           PasswordResetTokenRepository passwordResetTokenRepository,
                           RefreshTokenRepository refreshTokenRepository, EmailService emailService,
                            IdentityRateLimiter identityRateLimiter, NotificationService notifications,
                            PlatformTransactionManager transactionManager,
                            @Qualifier("applicationTaskExecutor") AsyncTaskExecutor recoveryExecutor) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.emailService = emailService;
        this.identityRateLimiter = identityRateLimiter;
        this.notifications = notifications;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.recoveryExecutor = recoveryExecutor;
    }

    public void forgotPassword(String email) {
        if (email != null && !email.isBlank()) {
            identityRateLimiter.check("forgot-password", email, 3, 3);
        }

        recoveryExecutor.execute(() -> {
            try {
                sendPasswordReset(email);
            } catch (RuntimeException failure) {
                SafeLog.failure(log, "password_recovery_failed", "INTERNAL_ERROR", failure);
            }
        });
    }

    private void sendPasswordReset(String email) {
        userRepository.findByEmail(email).ifPresent(user -> {
            UUID observedVersion = user.getSessionVersion();
            String token = transactionTemplate.execute(status -> {
                User current = lockUser(user.getId());
                if (!java.util.Objects.equals(current.getSessionVersion(), observedVersion)) return null;
                passwordResetTokenRepository.deleteByUserId(current.getId());
                return createPasswordResetToken(current);
            });
            if (token != null) emailService.sendPasswordResetEmail(user, token);
        });
    }

    @Transactional(dontRollbackOn = InvalidTokenException.class)
    public void resetPassword(String token, String newPassword) {
        String hash = OpaqueToken.hash(token);
        Long userId = passwordResetTokenRepository.findUserIdByTokenHash(hash)
                .orElseThrow(() -> new InvalidTokenException("Invalid or expired reset link"));
        User user = lockUser(userId);
        PasswordResetToken resetToken = passwordResetTokenRepository.findByTokenHashForUpdate(hash)
                .orElseThrow(() -> new InvalidTokenException("Invalid or expired reset link"));

        if (!resetToken.getExpiresAt().isAfter(Instant.now())) {
            passwordResetTokenRepository.delete(resetToken);
            throw new InvalidTokenException("Invalid or expired reset link");
        }

        if (user.getPassword() != null && passwordEncoder.matches(newPassword, user.getPassword())) {
            throw new RequestValidationException(RequestErrorCode.NEW_PASSWORD_UNCHANGED);
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        user.setSessionVersion(UUID.randomUUID());
        userRepository.save(user);
        passwordResetTokenRepository.deleteByUserId(user.getId());

        refreshTokenRepository.deleteByUserId(user.getId());
        notifications.revokeSessionsAfterCommit(user.getId());
    }

    @Transactional
    public void changePassword(User principal, String currentPassword, String newPassword) {
        User user = lockUser(principal.getId());
        if (user.getPassword() == null || !passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw new RequestValidationException(RequestErrorCode.CURRENT_PASSWORD_INCORRECT);
        }
        if (passwordEncoder.matches(newPassword, user.getPassword())) {
            throw new RequestValidationException(RequestErrorCode.NEW_PASSWORD_UNCHANGED);
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        user.setSessionVersion(UUID.randomUUID());
        userRepository.save(user);
        passwordResetTokenRepository.deleteByUserId(user.getId());
        refreshTokenRepository.deleteByUserId(user.getId());
        notifications.revokeSessionsAfterCommit(user.getId());
    }

    private User lockUser(Long id) {
        User user = userRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        entityManager.refresh(user);
        return user;
    }

    private String createPasswordResetToken(User user) {
        String rawToken = OpaqueToken.generate();
        PasswordResetToken token = new PasswordResetToken();
        token.setUser(user);
        token.setTokenHash(OpaqueToken.hash(rawToken));
        token.setExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS));
        passwordResetTokenRepository.save(token);
        return rawToken;
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
