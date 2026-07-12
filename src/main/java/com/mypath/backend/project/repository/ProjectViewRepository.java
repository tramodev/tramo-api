package com.mypath.backend.project.repository;

import com.mypath.backend.project.entity.ProjectView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProjectViewRepository extends JpaRepository<ProjectView, Long> {
    boolean existsByProjectIdAndViewerKey(Long projectId, String viewerKey);
    void deleteByProjectId(Long projectId);
}
