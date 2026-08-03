package com.tramo.backend.auth.repository;

import com.tramo.backend.auth.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    Optional<RefreshToken> findByToken(String token);

    void deleteByToken(String token);

    void deleteByUserId(Long userId);

    // Only expiresAt matters here, not revoked: a rotated (revoked) token must stay
    // queryable by findByToken until its natural expiry, since refresh() relies on
    // finding it to detect reuse of a stolen token and nuke the whole session.
    long deleteByExpiresAtBefore(Instant cutoff);

    // Grace period after revocation, not full expiry: revoked tokens only need to
    // stay queryable long enough to catch a delayed reuse/theft attempt (see
    // AuthService.refresh()'s isRevoked() branch), not for the token's full 30-day
    // lifetime — that would accumulate ~96 dead rows/day for an active user.
    long deleteByRevokedTrueAndRevokedAtBefore(Instant cutoff);

}
