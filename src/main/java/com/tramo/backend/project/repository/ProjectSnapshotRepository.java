// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.project.repository;

import com.tramo.backend.project.entity.ProjectSnapshot;
import com.tramo.backend.project.dto.ProjectSnapshotSummaryDTO;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;

@Repository
public interface ProjectSnapshotRepository extends JpaRepository<ProjectSnapshot, Long> {
    @Modifying(flushAutomatically = true)
    @Query("delete from ProjectSnapshot s where s.project.id = :projectId")
    void deleteByProjectId(@Param("projectId") Long projectId);

    @Query("select max(s.version) from ProjectSnapshot s where s.project.id = :projectId and s.trigger = 'PUBLISH'")
    Optional<Integer> findMaxVersion(@Param("projectId") Long projectId);

    @Query("select new com.tramo.backend.project.dto.ProjectSnapshotSummaryDTO(s.id, s.version, s.createdDate) " +
            "from ProjectSnapshot s where s.project.id = :projectId and s.trigger = :trigger order by s.version desc")
    List<ProjectSnapshotSummaryDTO> findByProjectIdAndTriggerOrderByVersionDesc(Long projectId, String trigger);

    @Override
    @Query(value = "SELECT id, project_id, trigger, version, created_date, " +
            "CASE WHEN octet_length(content) <= :#{@snapshotLimits.maxBytes()} THEN content END AS content " +
            "FROM project_snapshot WHERE id = :id", nativeQuery = true)
    Optional<ProjectSnapshot> findById(@Param("id") Long id);

    
    
    
    @Query(value = "SELECT s.id, s.project_id, s.trigger, s.version, s.created_date, " +
            "CASE WHEN sum(octet_length(s.content)) OVER () <= :#{@snapshotLimits.maxBytes()} THEN s.content END AS content " +
            "FROM project_snapshot s WHERE s.trigger = 'PUBLISH' AND s.id IN " +
            "(SELECT MAX(s2.id) FROM project_snapshot s2 WHERE s2.trigger = 'PUBLISH' AND s2.project_id IN :projectIds GROUP BY s2.project_id)", nativeQuery = true)
    List<ProjectSnapshot> findLatestPublishByProjectIdIn(@Param("projectIds") List<Long> projectIds);

    @Query("SELECT DISTINCT s.project.id FROM ProjectSnapshot s WHERE s.trigger = 'PUBLISH'")
    List<Long> findProjectIdsWithPublishSnapshot();
}
