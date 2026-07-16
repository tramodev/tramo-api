package com.mypath.backend.moderation.repository;

import com.mypath.backend.moderation.entity.ProjectReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProjectReportRepository extends JpaRepository<ProjectReport, Long> {
    List<ProjectReport> findByStatusOrderByCreatedDateDesc(String status);

    boolean existsByProjectIdAndReporterIdAndStatus(Long projectId, Long reporterId, String status);

    void deleteByProjectId(Long projectId);
    void deleteByReporterId(Long reporterId);
}
