// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.auth.service;

import com.tramo.backend.auth.dto.AuthResponse;
import com.tramo.backend.exception.RequestErrorCode;
import com.tramo.backend.exception.RequestValidationException;
import com.tramo.backend.user.Role;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.user.repository.UserRepository;
import jakarta.transaction.Transactional;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class GoogleAuthService {
    private static final int SEQUENTIAL_USERNAME_ATTEMPTS = 5;

    private final UserRepository userRepository;
    private final GoogleTokenVerifier googleTokenVerifier;
    private final SessionService sessionService;
    private final PasswordEncoder passwordEncoder;
    private final IdentityRateLimiter identityRateLimiter;

    public GoogleAuthService(UserRepository userRepository, GoogleTokenVerifier googleTokenVerifier,
                             SessionService sessionService, PasswordEncoder passwordEncoder,
                             IdentityRateLimiter identityRateLimiter) {
        this.userRepository = userRepository;
        this.googleTokenVerifier = googleTokenVerifier;
        this.sessionService = sessionService;
        this.passwordEncoder = passwordEncoder;
        this.identityRateLimiter = identityRateLimiter;
    }

    @Transactional
    public AuthResponse googleAuth(String idToken, String password) {
        GoogleTokenVerifier.GoogleTokenPayload payload = googleTokenVerifier.verify(idToken);

        User user = userRepository.findByGoogleSub(payload.sub())
                .orElseGet(() -> userRepository.findByEmailForUpdate(payload.email())
                        .map(existing -> linkGoogleUser(existing, payload.sub(), password))
                        .orElseGet(() -> createGoogleUser(payload)));

        if (!user.isEnabled() || !user.isAccountNonLocked()) {
            throw new BadCredentialsException("Invalid account");
        }

        return sessionService.issueSession(user);
    }

    private User linkGoogleUser(User user, String sub, String password) {
        if (user.isBanned()) {
            throw new BadCredentialsException("Invalid account");
        }
        if (sub.equals(user.getGoogleSub())) {
            return user;
        }
        if (user.getGoogleSub() != null) {
            throw new RequestValidationException(RequestErrorCode.GOOGLE_ALREADY_LINKED);
        }
        if (!user.isEmailVerified()) {
            throw new RequestValidationException(RequestErrorCode.GOOGLE_LINK_VERIFICATION_REQUIRED);
        }
        if (password == null || password.isBlank() || user.getPassword() == null) {
            throw new RequestValidationException(RequestErrorCode.GOOGLE_LINK_REQUIRED);
        }
        identityRateLimiter.check("google-link", user.getEmail(), 10, 10);
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new RequestValidationException(RequestErrorCode.CURRENT_PASSWORD_INCORRECT);
        }
        user.setGoogleSub(sub);
        return userRepository.save(user);
    }

    private User createGoogleUser(GoogleTokenVerifier.GoogleTokenPayload payload) {
        User user = new User();
        user.setUsername(generateUsernameFromEmail(payload.email()));
        user.setEmail(payload.email());
        user.setGoogleSub(payload.sub());
        user.setPassword(null);
        user.setVisibility(true);
        user.setCreatedAt(new Date());
        user.setUpdatedAt(new Date());
        user.setRole(Role.USER);
        user.setEmailVerified(true);
        return userRepository.save(user);
    }

    private String generateUsernameFromEmail(String email) {
        String base = email.substring(0, email.indexOf('@')).replaceAll("[^a-zA-Z0-9_]", "");
        if (base.length() < 3) {
            base = (base + "user").substring(0, Math.max(3, base.length()));
        }
        base = base.substring(0, Math.min(base.length(), 20));

        String candidate = base;
        for (int attempt = 0; userRepository.existsByUsernameIgnoreCase(candidate); attempt++) {
            String suffix = attempt < SEQUENTIAL_USERNAME_ATTEMPTS
                    ? String.valueOf(attempt + 1)
                    : String.valueOf(ThreadLocalRandom.current().nextInt(1_000_000, 10_000_000));
            candidate = base.substring(0, Math.min(base.length(), 20 - suffix.length())) + suffix;
        }
        return candidate;
    }
}
