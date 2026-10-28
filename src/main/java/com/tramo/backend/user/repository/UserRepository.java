// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.user.repository;

import com.tramo.backend.user.entity.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.Collection;
import java.util.UUID;


public interface UserRepository extends JpaRepository<User, Long> {
    @Query("select u.id as id, u.sessionVersion as sessionVersion, u.banned as banned from User u where u.id in :ids")
    List<SessionState> findSessionStates(@Param("ids") Collection<Long> ids);

    interface SessionState {
        Long getId();
        UUID getSessionVersion();
        boolean getBanned();
    }

    @Query(value = "SELECT id FROM users WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<Long> lockStorageById(@Param("id") Long id);
    Optional<User> findByUsernameIgnoreCase(String username);
    boolean existsByUsernameIgnoreCase(String username);
    @Query("select (count(u) > 0) from User u where lower(u.email) = lower(:email)")
    boolean existsByEmail(@Param("email") String email);
    @Query("select u from User u where lower(u.email) = lower(:email)")
    Optional<User> findByEmail(@Param("email") String email);
    Optional<User> findByGoogleSub(String googleSub);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where lower(u.email) = lower(:email)")
    Optional<User> findByEmailForUpdate(@Param("email") String email);
    @Query("SELECT u FROM User u WHERE :q = '' OR LOWER(u.username) LIKE LOWER(CONCAT('%', :q, '%')) ESCAPE '!' "
            + "OR LOWER(u.email) LIKE LOWER(CONCAT('%', :q, '%')) ESCAPE '!' ORDER BY u.id ASC")
    List<User> searchByUsernameOrEmail(@Param("q") String q, Pageable pageable);
    Optional<User> findByPatreonUserId(String patreonUserId);
    List<User> findByVisibilityTrueAndBannedFalse();
}
