package com.tramo.backend.auth.repository;

import com.tramo.backend.auth.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;

import org.springframework.data.jpa.repository.Query;

import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    Optional<RefreshToken> findByToken(String token);

    @Modifying(flushAutomatically = true)
    @Query("delete from RefreshToken t where t.token = :token")
    void deleteByToken(@Param("token") String token);

    @Modifying(flushAutomatically = true)
    @Query("delete from RefreshToken t where t.user.id = :userId")
    void deleteByUserId(@Param("userId") Long userId);

    @Modifying(flushAutomatically = true)
    @Query("delete from RefreshToken t where t.expiresAt < :cutoff")
    int deleteByExpiresAtBefore(@Param("cutoff") Instant cutoff);

    @Modifying(flushAutomatically = true)
    @Query("delete from RefreshToken t where t.revoked = true and t.revokedAt < :cutoff")
    int deleteByRevokedTrueAndRevokedAtBefore(@Param("cutoff") Instant cutoff);

}
