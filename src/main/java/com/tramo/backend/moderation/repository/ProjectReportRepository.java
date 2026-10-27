// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.moderation.repository;

import com.tramo.backend.moderation.entity.ProjectReport;
import com.tramo.backend.moderation.entity.ReportStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;


import org.springframework.data.jpa.repository.Modifying;

public interface ProjectReportRepository extends JpaRepository<ProjectReport, Long> {



    @Modifying(flushAutomatically = true)
    @Query("UPDATE ProjectReport r SET r.status = :to WHERE r.project.id = :projectId AND r.status = :from")
    int updateStatusByProjectId(@Param("projectId") Long projectId, @Param("from") ReportStatus from, @Param("to") ReportStatus to);

    @Query(value = """
            SELECT * FROM (
                SELECT r.id, 'PROJECT' AS type, p.id AS project_id, p.title, CAST(NULL AS bigint) AS comment_id,
                    CAST(NULL AS text) AS content, u.username, r.reason, r.status, r.created_date
                FROM project_report r JOIN project p ON p.id = r.project_id JOIN users u ON u.id = r.reporter_id
                UNION ALL
                SELECT r.id, 'COMMENT' AS type, p.id AS project_id, p.title, c.id AS comment_id,
                    c.content, u.username, r.reason, r.status, r.created_date
                FROM comment_report r JOIN comment c ON c.id = r.comment_id JOIN project p ON p.id = c.project_id
                    JOIN users u ON u.id = r.reporter_id
            ) reports WHERE status = :status AND (:type = 'ALL' OR type = :type)
            ORDER BY created_date DESC, type, id DESC
            """, nativeQuery = true)
    Slice<Object[]> findReportRows(String type, String status, Pageable pageable);

    boolean existsByProjectIdAndReporterIdAndStatus(Long projectId, Long reporterId, ReportStatus status);

    @Modifying(flushAutomatically = true)
    @Query("delete from ProjectReport r where r.project.id = :projectId")
    void deleteByProjectId(@Param("projectId") Long projectId);
    @Modifying(flushAutomatically = true)
    @Query("delete from ProjectReport r where r.reporter.id = :reporterId")
    void deleteByReporterId(@Param("reporterId") Long reporterId);
}
