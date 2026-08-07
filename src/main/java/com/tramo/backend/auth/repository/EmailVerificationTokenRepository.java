package com.tramo.backend.auth.repository;

import com.tramo.backend.auth.entity.EmailVerificationToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;

import org.springframework.data.jpa.repository.Query;

import org.springframework.data.repository.query.Param;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, UUID> {
    Optional<EmailVerificationToken> findByToken(String token);
    @Modifying(flushAutomatically = true)
    @Query("delete from EmailVerificationToken t where t.user.id = :userId")
    void deleteByUserId(@Param("userId") Long userId);
    @Modifying(flushAutomatically = true)
    @Query("delete from EmailVerificationToken t where t.expiresAt < :cutoff")
    int deleteByExpiresAtBefore(@Param("cutoff") Instant cutoff);
}
