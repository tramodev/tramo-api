// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.project.repository;

import com.tramo.backend.project.entity.ProjectMember;
import com.tramo.backend.project.entity.ProjectRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectMemberRepository extends JpaRepository<ProjectMember, Long> {
    @Query("SELECT m.role FROM ProjectMember m WHERE m.project.id = :projectId AND m.user.id = :userId")
    Optional<ProjectRole> findRole(@Param("projectId") Long projectId, @Param("userId") Long userId);

    Optional<ProjectMember> findByProjectIdAndUserId(Long projectId, Long userId);

    long countByProjectId(Long projectId);

    @Query("SELECT m FROM ProjectMember m JOIN FETCH m.user WHERE m.project.id = :projectId ORDER BY m.createdAt, m.id")
    List<ProjectMember> findWithUserByProjectId(@Param("projectId") Long projectId);

    @Query("SELECT m.user.id FROM ProjectMember m WHERE m.shareLink.id = :shareLinkId")
    List<Long> findUserIdsByShareLinkId(@Param("shareLinkId") Long shareLinkId);

    @Modifying(flushAutomatically = true)
    @Query("delete from ProjectMember m where m.shareLink.id = :shareLinkId")
    void deleteByShareLinkId(@Param("shareLinkId") Long shareLinkId);

    @Modifying(flushAutomatically = true)
    @Query("delete from ProjectMember m where (m.user.id = :a and m.project.id in (select p.id from Project p where p.owner.id = :b))"
            + " or (m.user.id = :b and m.project.id in (select p.id from Project p where p.owner.id = :a))")
    void deleteBetween(@Param("a") Long a, @Param("b") Long b);
}
