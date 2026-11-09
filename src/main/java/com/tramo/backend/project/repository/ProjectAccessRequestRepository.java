// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.project.repository;

import com.tramo.backend.project.entity.ProjectAccessRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProjectAccessRequestRepository extends JpaRepository<ProjectAccessRequest, Long> {
    boolean existsByProjectIdAndRequesterId(Long projectId, Long requesterId);

    @Query("SELECT r FROM ProjectAccessRequest r JOIN FETCH r.requester WHERE r.project.id = :projectId ORDER BY r.createdAt, r.id")
    List<ProjectAccessRequest> findWithRequesterByProjectId(@Param("projectId") Long projectId);

    @Modifying(flushAutomatically = true)
    @Query("delete from ProjectAccessRequest r where r.project.id = :projectId and r.requester.id = :requesterId")
    int deleteByProjectIdAndRequesterId(@Param("projectId") Long projectId, @Param("requesterId") Long requesterId);

    @Modifying(flushAutomatically = true)
    @Query("delete from ProjectAccessRequest r where (r.requester.id = :a and r.project.id in (select p.id from Project p where p.owner.id = :b))"
            + " or (r.requester.id = :b and r.project.id in (select p.id from Project p where p.owner.id = :a))")
    void deleteBetween(@Param("a") Long a, @Param("b") Long b);
}
