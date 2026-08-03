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

}
