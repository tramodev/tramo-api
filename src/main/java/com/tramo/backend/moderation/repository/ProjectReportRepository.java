package com.tramo.backend.moderation.repository;

import com.tramo.backend.moderation.entity.ProjectReport;
import com.tramo.backend.moderation.entity.ReportStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProjectReportRepository extends JpaRepository<ProjectReport, Long> {
    List<ProjectReport> findByStatusOrderByCreatedDateDesc(ReportStatus status);




    @Query("SELECT r.id, p.id, p.title, rep.username, r.reason, r.status, r.createdDate " +
            "FROM ProjectReport r JOIN r.project p JOIN r.reporter rep " +
            "WHERE r.status = :status ORDER BY r.createdDate DESC")
    List<Object[]> findOpenRows(@Param("status") ReportStatus status);

    boolean existsByProjectIdAndReporterIdAndStatus(Long projectId, Long reporterId, ReportStatus status);

    void deleteByProjectId(Long projectId);
    void deleteByReporterId(Long reporterId);
}
