package com.tramo.backend.moderation.repository;

import com.tramo.backend.moderation.entity.ProjectReport;
import com.tramo.backend.moderation.entity.ReportStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

import org.springframework.data.jpa.repository.Modifying;

public interface ProjectReportRepository extends JpaRepository<ProjectReport, Long> {



    @Query("SELECT r.id, p.id, p.title, rep.username, r.reason, r.status, r.createdDate " +
            "FROM ProjectReport r JOIN r.project p JOIN r.reporter rep " +
            "WHERE r.status = :status ORDER BY r.createdDate DESC")
    List<Object[]> findOpenRows(@Param("status") ReportStatus status, Pageable pageable);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE ProjectReport r SET r.status = :to WHERE r.project.id = :projectId AND r.status = :from")
    int updateStatusByProjectId(@Param("projectId") Long projectId, @Param("from") ReportStatus from, @Param("to") ReportStatus to);

    boolean existsByProjectIdAndReporterIdAndStatus(Long projectId, Long reporterId, ReportStatus status);

    @Modifying(flushAutomatically = true)
    @Query("delete from ProjectReport r where r.project.id = :projectId")
    void deleteByProjectId(@Param("projectId") Long projectId);
    @Modifying(flushAutomatically = true)
    @Query("delete from ProjectReport r where r.reporter.id = :reporterId")
    void deleteByReporterId(@Param("reporterId") Long reporterId);
}
