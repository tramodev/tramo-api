package com.tramo.backend.auth.repository;

import com.tramo.backend.auth.entity.EmailVerificationToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, UUID> {
    Optional<EmailVerificationToken> findByToken(String token);
    void deleteByUserId(Long userId);
    long deleteByExpiresAtBefore(Instant cutoff);
}
