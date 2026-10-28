// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.auth.service;

import com.tramo.backend.auth.dto.AuthResponse;
import com.tramo.backend.auth.dto.AvailabilityResponseDTO;
import com.tramo.backend.auth.dto.RegisterRequestDTO;
import com.tramo.backend.auth.dto.RegisterResponseDTO;
import com.tramo.backend.auth.entity.EmailVerificationToken;
import com.tramo.backend.auth.repository.EmailVerificationTokenRepository;
import com.tramo.backend.exception.InvalidTokenException;
import com.tramo.backend.exception.UserAlreadyExistsException;
import com.tramo.backend.user.Role;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.user.repository.UserRepository;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Optional;
import java.util.Locale;

@Service
public class RegistrationService {
    private static final String REGISTERED_MESSAGE = "Account created. Check your email to verify your account.";
    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailVerificationTokenRepository emailVerificationTokenRepository;
    private final EmailService emailService;
    private final CaptchaVerifier captchaVerifier;
    private final TransactionTemplate transactionTemplate;
    private final IdentityRateLimiter identityRateLimiter;
    private final AgeGateService ageGateService;
    private final SessionService sessionService;

    public RegistrationService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                               EmailVerificationTokenRepository emailVerificationTokenRepository,
                               EmailService emailService, CaptchaVerifier captchaVerifier,
                               PlatformTransactionManager transactionManager,
                               IdentityRateLimiter identityRateLimiter, AgeGateService ageGateService,
                               SessionService sessionService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailVerificationTokenRepository = emailVerificationTokenRepository;
        this.emailService = emailService;
        this.captchaVerifier = captchaVerifier;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.identityRateLimiter = identityRateLimiter;
        this.ageGateService = ageGateService;
        this.sessionService = sessionService;
    }

    public AvailabilityResponseDTO checkUsernameAvailability(String username) {
        boolean available = username != null && !username.isBlank()
                && !userRepository.existsByUsernameIgnoreCase(username);
        return new AvailabilityResponseDTO(available);
    }

    public RegisterResponseDTO register(RegisterRequestDTO registerRequest, String ip) {
        ageGateService.assertNotInCooldown(ip);

        captchaVerifier.verify(registerRequest.getCaptchaToken(), "register");

        if (userRepository.existsByUsernameIgnoreCase(registerRequest.getUsername())) {
            throw new UserAlreadyExistsException("username", "Username '" + registerRequest.getUsername() + "' is already taken");
        }

        ageGateService.validateOrRecordRejection(ip, registerRequest.getBirthDate());

        String encodedPassword = passwordEncoder.encode(registerRequest.getPassword());
        if (userRepository.existsByEmail(registerRequest.getEmail())) {
            return new RegisterResponseDTO(REGISTERED_MESSAGE);
        }

        User user = new User();
        user.setUsername(registerRequest.getUsername());
        user.setPassword(encodedPassword);
        user.setEmail(registerRequest.getEmail().toLowerCase(Locale.ROOT));
        user.setBirthDate(registerRequest.getBirthDate());
        user.setVisibility(registerRequest.getVisibility() != null ? registerRequest.getVisibility() : true);
        user.setCreatedAt(new Date());
        user.setUpdatedAt(new Date());
        user.setRole(Role.USER);
        user.setEmailVerified(false);

        String verificationToken;
        try {
            verificationToken = transactionTemplate.execute(status -> {
                userRepository.save(user);
                return createVerificationToken(user);
            });
        } catch (DataIntegrityViolationException e) {
            if (userRepository.existsByUsernameIgnoreCase(registerRequest.getUsername())) {
                throw new UserAlreadyExistsException("username", "Username '" + registerRequest.getUsername() + "' is already taken");
            }
            return new RegisterResponseDTO(REGISTERED_MESSAGE);
        }
        emailService.sendVerificationEmail(user, verificationToken);

        return new RegisterResponseDTO(REGISTERED_MESSAGE);
    }

    @Transactional(dontRollbackOn = InvalidTokenException.class)
    public AuthResponse verifyEmail(String token) {
        EmailVerificationToken verificationToken = emailVerificationTokenRepository.findByTokenHash(OpaqueToken.hash(token))
                .orElseThrow(() -> new InvalidTokenException("Invalid or expired verification link"));

        if (verificationToken.getExpiresAt().isBefore(Instant.now())) {
            emailVerificationTokenRepository.delete(verificationToken);
            throw new InvalidTokenException("Invalid or expired verification link");
        }

        User user = verificationToken.getUser();
        user.setEmailVerified(true);
        userRepository.save(user);
        emailVerificationTokenRepository.delete(verificationToken);

        return sessionService.issueSession(user);
    }

    public void resendVerification(String username, String email) {
        Optional<User> userOpt = Optional.empty();
        if (username != null && !username.isBlank()) {
            userOpt = userRepository.findByUsernameIgnoreCase(username);
        } else if (email != null && !email.isBlank()) {
            userOpt = userRepository.findByEmail(email);
        }

        String identifier = userOpt.map(User::getEmail).orElse(
                username != null && !username.isBlank() ? username : email);
        if (identifier != null && !identifier.isBlank()) {
            identityRateLimiter.check("resend-verification", identifier, 3, 3);
        }
        userOpt.ifPresent(user -> {
            if (!user.isEmailVerified()) {
                String token = transactionTemplate.execute(status -> {
                    emailVerificationTokenRepository.deleteByUserId(user.getId());
                    return createVerificationToken(user);
                });
                emailService.sendVerificationEmail(user, token);
            }
        });
    }

    private String createVerificationToken(User user) {
        EmailVerificationToken evt = new EmailVerificationToken();
        evt.setUser(user);
        String rawToken = OpaqueToken.generate();
        evt.setTokenHash(OpaqueToken.hash(rawToken));
        evt.setExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));
        emailVerificationTokenRepository.save(evt);
        return rawToken;
    }

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void purgeExpiredVerificationTokens() {
        long deletedVerificationTokens = emailVerificationTokenRepository.deleteByExpiresAtBefore(Instant.now());
        if (deletedVerificationTokens > 0) {
            log.info("purgeExpiredVerificationTokens deleted {} expired email verification tokens", deletedVerificationTokens);
        }
    }
}
