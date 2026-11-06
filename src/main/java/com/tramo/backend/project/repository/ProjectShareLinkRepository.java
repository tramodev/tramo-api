// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.project.repository;

import com.tramo.backend.project.entity.ProjectShareLink;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProjectShareLinkRepository extends JpaRepository<ProjectShareLink, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM ProjectShareLink l WHERE l.tokenHash = :tokenHash")
    Optional<ProjectShareLink> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM ProjectShareLink l WHERE l.project.id = :projectId AND l.revokedAt IS NULL")
    Optional<ProjectShareLink> findActiveForUpdate(@Param("projectId") Long projectId);

    Optional<ProjectShareLink> findByProjectIdAndRevokedAtIsNull(Long projectId);

    Optional<ProjectShareLink> findByTokenHash(String tokenHash);
}
