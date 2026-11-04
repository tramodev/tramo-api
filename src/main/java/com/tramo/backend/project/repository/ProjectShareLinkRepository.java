// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.project.repository;

import com.tramo.backend.project.entity.ProjectRole;
import com.tramo.backend.project.entity.ProjectShareLink;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectShareLinkRepository extends JpaRepository<ProjectShareLink, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM ProjectShareLink l WHERE l.tokenHash = :tokenHash")
    Optional<ProjectShareLink> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM ProjectShareLink l WHERE l.id = :id AND l.project.id = :projectId")
    Optional<ProjectShareLink> findByIdAndProjectIdForUpdate(@Param("id") Long id, @Param("projectId") Long projectId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM ProjectShareLink l WHERE l.project.id = :projectId AND l.role = :role AND l.revokedAt IS NULL")
    Optional<ProjectShareLink> findActiveForUpdate(@Param("projectId") Long projectId, @Param("role") ProjectRole role);

    List<ProjectShareLink> findByProjectIdAndRevokedAtIsNullOrderByCreatedAtAsc(Long projectId);
}
