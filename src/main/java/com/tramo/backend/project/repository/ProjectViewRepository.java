// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.project.repository;

import com.tramo.backend.project.entity.ProjectView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@Repository
public interface ProjectViewRepository extends JpaRepository<ProjectView, Long> {
    @Modifying
    @Query(value = "INSERT INTO project_view (id, project_id, viewer_key, created_date) "
            + "VALUES (nextval('project_view_seq'), :projectId, :viewerKey, CURRENT_TIMESTAMP) "
            + "ON CONFLICT (project_id, viewer_key) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("projectId") Long projectId, @Param("viewerKey") String viewerKey);
    @Modifying(flushAutomatically = true)
    @Query("delete from ProjectView v where v.project.id = :projectId")
    void deleteByProjectId(@Param("projectId") Long projectId);
}
