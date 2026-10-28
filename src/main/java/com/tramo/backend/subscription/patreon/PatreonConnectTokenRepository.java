// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
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
    @Query("delete from PatreonConnectToken t where t.token = :token and t.user.id = :userId and t.expiresAt > :now")
    int consume(@Param("token") String token, @Param("userId") Long userId, @Param("now") java.time.Instant now);
    @Modifying(flushAutomatically = true)
    @Query("delete from PatreonConnectToken t where t.user.id = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
