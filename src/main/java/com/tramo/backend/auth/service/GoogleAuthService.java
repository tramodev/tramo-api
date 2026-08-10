package com.tramo.backend.auth.service;

import com.tramo.backend.auth.dto.AuthResponse;
import com.tramo.backend.user.Role;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.user.repository.UserRepository;
import jakarta.transaction.Transactional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class GoogleAuthService {
    private static final int SEQUENTIAL_USERNAME_ATTEMPTS = 5;

    private final UserRepository userRepository;
    private final GoogleTokenVerifier googleTokenVerifier;
    private final SessionService sessionService;

    public GoogleAuthService(UserRepository userRepository, GoogleTokenVerifier googleTokenVerifier,
                             SessionService sessionService) {
        this.userRepository = userRepository;
        this.googleTokenVerifier = googleTokenVerifier;
        this.sessionService = sessionService;
    }

    @Transactional
    public AuthResponse googleAuth(String idToken) {
        GoogleTokenVerifier.GoogleTokenPayload payload = googleTokenVerifier.verify(idToken);

        User user = userRepository.findByEmail(payload.email())
                .orElseGet(() -> createGoogleUser(payload));

        if (!user.isEmailVerified()) {
            user.setEmailVerified(true);
            userRepository.save(user);
        }

        return sessionService.issueSession(user);
    }

    private User createGoogleUser(GoogleTokenVerifier.GoogleTokenPayload payload) {
        User user = new User();
        user.setUsername(generateUsernameFromEmail(payload.email()));
        user.setEmail(payload.email());
        user.setPassword(null);
        user.setVisibility(true);
        user.setCreatedAt(new Date());
        user.setUpdatedAt(new Date());
        user.setRole(Role.USER);
        user.setEmailVerified(true);
        try {
            return userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            return userRepository.findByEmail(payload.email()).orElseThrow(() -> e);
        }
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
