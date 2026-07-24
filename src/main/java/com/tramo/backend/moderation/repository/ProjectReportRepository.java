package com.tramo.backend.moderation.repository;

import com.tramo.backend.moderation.entity.ProjectReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProjectReportRepository extends JpaRepository<ProjectReport, Long> {
    List<ProjectReport> findByStatusOrderByCreatedDateDesc(String status);

    // Scalar projection for the admin list: a plain join avoids loading the Project
    // entity (whose EAGER owner/forkedFrom would each be an N+1 per report).
    // Columns: reportId, projectId, projectTitle, reporterUsername, reason, status, createdDate.
    @Query("SELECT r.id, p.id, p.title, rep.username, r.reason, r.status, r.createdDate " +
            "FROM ProjectReport r JOIN r.project p JOIN r.reporter rep " +
            "WHERE r.status = :status ORDER BY r.createdDate DESC")
    List<Object[]> findOpenRows(@Param("status") String status);

    boolean existsByProjectIdAndReporterIdAndStatus(Long projectId, Long reporterId, String status);

    void deleteByProjectId(Long projectId);
    void deleteByReporterId(Long reporterId);
}
