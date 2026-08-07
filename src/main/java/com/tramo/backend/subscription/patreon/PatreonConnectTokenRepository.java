package com.tramo.backend.subscription.patreon;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;

import org.springframework.data.jpa.repository.Query;

import org.springframework.data.repository.query.Param;

public interface PatreonConnectTokenRepository extends JpaRepository<PatreonConnectToken, UUID> {
    Optional<PatreonConnectToken> findByToken(String token);
    @Modifying(flushAutomatically = true)
    @Query("delete from PatreonConnectToken t where t.user.id = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
