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

    // Only expiresAt matters here, not revoked: a rotated (revoked) token must stay
    // queryable by findByToken until its natural expiry, since refresh() relies on
    // finding it to detect reuse of a stolen token and nuke the whole session.
    @Modifying(flushAutomatically = true)
    @Query("delete from RefreshToken t where t.expiresAt < :cutoff")
    int deleteByExpiresAtBefore(@Param("cutoff") Instant cutoff);

    // Grace period after revocation, not full expiry: revoked tokens only need to
    // stay queryable long enough to catch a delayed reuse/theft attempt (see
    // AuthService.refresh()'s isRevoked() branch), not for the token's full 30-day
    // lifetime — that would accumulate ~96 dead rows/day for an active user.
    @Modifying(flushAutomatically = true)
    @Query("delete from RefreshToken t where t.revoked = true and t.revokedAt < :cutoff")
    int deleteByRevokedTrueAndRevokedAtBefore(@Param("cutoff") Instant cutoff);

}
